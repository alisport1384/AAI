#!/usr/bin/env python3
"""
train_qwen3_persian.py — Fine-tune Qwen 3 / Qwen 2.5 models on Persian SFT & Instruct datasets.

Supported Datasets:
 - amiralifirouzi/tiny-persian-sft-15k (ChatML 'messages' format)
 - ParsiAI/FarsInstruct ('inputs' and 'outputs' format)
 - Standard Alpaca / Instruction datasets ('instruction', 'input', 'output' format)
 - Any local JSON / JSONL files

Target Models:
 - Qwen/Qwen3-4B-Instruct
 - Qwen/Qwen2.5-0.5B-Instruct
 - Qwen/Qwen2.5-1.5B-Instruct
 - unsloth/Qwen2.5-1.5B-Instruct

Features:
 - Automatic schema normalization: seamlessly handles 'messages', 'inputs'/'outputs', and 'instruction'/'output'.
 - Configurable dataset subsets (e.g. 'fa', 'fa_replay' for tiny-persian-sft-15k; 'default', 'pn_sum', 'persian_qa' for FarsInstruct).
 - Max samples capping (--max-samples) for efficient training on massive multi-million datasets like FarsInstruct.
 - Official Qwen ChatML template formatting (<|im_start|>role\ncontent<|im_end|>).
 - Loss masking on user instructions (train_on_responses_only).
 - Fast Unsloth QLoRA 4-bit / 16-bit LoRA fine-tuning.
 - Quantized GGUF export (q4_k_m, q8_0) for direct deployment to Hushyar Android app (llama.cpp).
 - Dry-run verification mode.

Usage:
  # Fast dry-run check on FarsInstruct:
  python3 scripts/train_qwen3_persian.py --dataset ParsiAI/FarsInstruct --dry-run

  # Full Fine-tuning on FarsInstruct with max 50,000 samples:
  python3 scripts/train_qwen3_persian.py \
      --model Qwen/Qwen2.5-0.5B-Instruct \
      --dataset ParsiAI/FarsInstruct \
      --dataset-config default \
      --max-samples 50000 \
      --output-dir ./qwen3-farsinstruct-lora \
      --epochs 1 \
      --export-gguf q4_k_m
"""

import argparse
import json
import os
import sys
from pathlib import Path


def parse_args(args=None):
    parser = argparse.ArgumentParser(
        description="Fine-tune Qwen 3 / Qwen 2.5 on Persian Datasets (tiny-persian-sft-15k, FarsInstruct, etc.)"
    )
    parser.add_argument(
        "--model",
        type=str,
        default="Qwen/Qwen2.5-0.5B-Instruct",
        help="Base model name or path (e.g. Qwen/Qwen2.5-0.5B-Instruct, Qwen/Qwen3-4B-Instruct)",
    )
    parser.add_argument(
        "--dataset",
        type=str,
        default="amiralifirouzi/tiny-persian-sft-15k",
        help="Hugging Face dataset identifier (e.g. ParsiAI/FarsInstruct, amiralifirouzi/tiny-persian-sft-15k) or local json/jsonl file path",
    )
    parser.add_argument(
        "--dataset-config",
        type=str,
        default="default",
        help="Dataset configuration split subset ('default', 'fa', 'pn_sum', 'persian_qa', etc.)",
    )
    parser.add_argument(
        "--max-samples",
        type=int,
        default=None,
        help="Maximum number of dataset samples to use for training (useful for massive datasets like FarsInstruct)",
    )
    parser.add_argument(
        "--max-seq-length",
        type=int,
        default=2048,
        help="Maximum sequence length in tokens (default: 2048)",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default="./qwen3-persian-lora",
        help="Directory where trained adapter / model checkpoint will be saved",
    )
    parser.add_argument(
        "--epochs",
        type=float,
        default=1.0,
        help="Number of training epochs (default: 1.0)",
    )
    parser.add_argument(
        "--per-device-batch-size",
        type=int,
        default=2,
        help="Batch size per device (default: 2)",
    )
    parser.add_argument(
        "--gradient-accumulation-steps",
        type=int,
        default=4,
        help="Gradient accumulation steps (default: 4)",
    )
    parser.add_argument(
        "--learning-rate",
        type=float,
        default=2e-4,
        help="Learning rate for LoRA training (default: 2e-4)",
    )
    parser.add_argument(
        "--lora-r",
        type=int,
        default=16,
        help="LoRA rank r (default: 16)",
    )
    parser.add_argument(
        "--lora-alpha",
        type=int,
        default=16,
        help="LoRA alpha parameter (default: 16)",
    )
    parser.add_argument(
        "--use-unsloth",
        action="store_true",
        default=True,
        help="Enable Unsloth fast training mode if installed",
    )
    parser.add_argument(
        "--export-gguf",
        type=str,
        default=None,
        choices=["q4_k_m", "q8_0", "f16", "q5_k_m"],
        help="Export quantized GGUF format after training for Hushyar mobile runtime",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Perform dry-run dataset loading and formatting check without loading heavy GPU weights",
    )
    return parser.parse_args(args)


def normalize_sample_to_messages(sample):
    """
    Normalizes different dataset schemas (messages, inputs/outputs, instruction/output)
    to a unified OpenAI ChatML messages list format.
    """
    if not isinstance(sample, dict):
        return []

    # 1. Format: 'messages' column (tiny-persian-sft-15k)
    if "messages" in sample and isinstance(sample["messages"], list):
        return sample["messages"]

    # 2. Format: 'inputs' and 'outputs' columns (ParsiAI/FarsInstruct)
    if "inputs" in sample and "outputs" in sample:
        inp = str(sample["inputs"] or "").strip()
        out = str(sample["outputs"] or "").strip()
        if inp and out:
            return [
                {"role": "user", "content": inp},
                {"role": "assistant", "content": out},
            ]

    # 3. Format: 'instruction', 'input', 'output' columns (Alpaca / standard instruction)
    if "instruction" in sample or "output" in sample:
        instruction = str(sample.get("instruction") or "").strip()
        inp_extra = str(sample.get("input") or "").strip()
        output = str(sample.get("output") or "").strip()
        user_text = f"{instruction}\n{inp_extra}".strip() if inp_extra else instruction
        if user_text and output:
            return [
                {"role": "user", "content": user_text},
                {"role": "assistant", "content": output},
            ]

    return []


def load_persian_dataset(dataset_name_or_path: str, config: str, max_samples: int = None):
    """
    Loads Persian datasets (e.g. ParsiAI/FarsInstruct, amiralifirouzi/tiny-persian-sft-15k, or local files).
    """
    if os.path.exists(dataset_name_or_path):
        print(f"==> Loading local dataset file: {dataset_name_or_path}")
        with open(dataset_name_or_path, "r", encoding="utf-8") as f:
            if dataset_name_or_path.endswith(".jsonl"):
                data = [json.loads(line) for line in f if line.strip()]
            else:
                data = json.load(f)
        if max_samples and isinstance(data, list):
            data = data[:max_samples]
        return data

    try:
        from datasets import load_dataset, concatenate_datasets
        print(f"==> Loading HuggingFace dataset: {dataset_name_or_path} (config={config})")

        # Config handling
        cfg = None if config in ("default", "all", "none") else config
        if dataset_name_or_path == "amiralifirouzi/tiny-persian-sft-15k" and config == "default":
            cfg = "fa"

        if config == "all" and dataset_name_or_path == "amiralifirouzi/tiny-persian-sft-15k":
            ds_fa = load_dataset(dataset_name_or_path, "fa", split="train")
            ds_replay = load_dataset(dataset_name_or_path, "fa_replay", split="train")
            ds = concatenate_datasets([ds_fa, ds_replay])
        else:
            ds = load_dataset(dataset_name_or_path, cfg, split="train")

        if max_samples and hasattr(ds, "select") and len(ds) > max_samples:
            print(f"==> Subsampling dataset from {len(ds)} to {max_samples} samples...")
            ds = ds.select(range(max_samples))

        return ds
    except ImportError:
        print("! 'datasets' package not installed; generating synthetic preview sample for verification...")
        if "FarsInstruct" in dataset_name_or_path or "farsinstruct" in dataset_name_or_path.lower():
            return [
                {
                    "dataset": "pn_summary",
                    "template": "gen_article_with_summary",
                    "inputs": "خلاصه داده شده را با دقت مطالعه کن و آن را در قالب یک مقاله کامل بسط بده",
                    "outputs": "فریده روشن در گفت وگو با خبرنگار ایمنا با اشاره به دستاوردهای نشست..."
                },
                {
                    "dataset": "parsinlu_translation_en_fa",
                    "template": "which_farsi_english",
                    "inputs": "این متن به کدامیک از زبان های فارسی یا انگلیسی نوشته شده است؟ متن: می بینی که. زبان متن:",
                    "outputs": "فارسی"
                }
            ]
        else:
            return [
                {
                    "id": "norobots-08386",
                    "task": "open qa",
                    "language": "fa",
                    "messages": [
                        {"role": "user", "content": "بهترین کتاب پرفروش تمام دوران چیست؟"},
                        {"role": "assistant", "content": "بیشترین کتاب پرفروش تاریخ، کتاب مقدس مسیحی است."}
                    ]
                }
            ]


def format_messages_sample(sample_messages):
    """
    Formats messages list to verify ChatML structure.
    """
    formatted = []
    for msg in sample_messages:
        role = msg.get("role", "")
        content = msg.get("content", "")
        formatted.append(f"<|im_start|>{role}\n{content}<|im_end|>")
    return "\n".join(formatted)


def run_dry_run(args):
    """
    Executes a dry-run check of the dataset, schema, and formatting.
    """
    print("==========================================================")
    print("  QWEN 3 / QWEN 2.5 PERSIAN SFT TRAINING PIPELINE (DRY RUN)")
    print("==========================================================")
    print(f"  Target Model   : {args.model}")
    print(f"  Target Dataset : {args.dataset} (config={args.dataset_config})")
    print(f"  Max Samples    : {args.max_samples if args.max_samples else 'All'}")
    print(f"  Output Dir     : {args.output_dir}")
    print(f"  Max Seq Length : {args.max_seq_length}")
    print(f"  LoRA Config    : r={args.lora_r}, alpha={args.lora_alpha}")
    print("==========================================================")

    try:
        ds = load_persian_dataset(args.dataset, args.dataset_config, args.max_samples)
        sample_count = len(ds)
        print(f"✓ Dataset loaded successfully! Total samples available: {sample_count}")

        sample = ds[0] if isinstance(ds, list) else ds[0]
        messages = normalize_sample_to_messages(sample)

        if not messages:
            print("❌ Error: Could not extract valid ChatML messages from dataset sample!", file=sys.stderr)
            sys.exit(1)

        print(f"✓ Original Columns   : {list(sample.keys())}")
        print(f"✓ Schema Standardized : Converted to {len(messages)} ChatML turns")
        print("\n--- First Turn Preview ---")
        for turn in messages[:2]:
            role = turn.get("role", "")
            content = turn.get("content", "")[:120].replace("\n", " ")
            print(f" [{role.upper()}]: {content}...")
        print("--------------------------\n")

        chatml_preview = format_messages_sample(messages)
        print(f"✓ ChatML Formatting Check:\n{chatml_preview[:300]}...\n")

        print("✓ DRY-RUN COMPLETED SUCCESSFULLY! All schema checks passed.")
        print("  To run full training on GPU:")
        print("  pip install unsloth trl transformers datasets accelerate peft")
        print(f"  python3 scripts/train_qwen3_persian.py --model {args.model} --dataset {args.dataset}\n")
    except Exception as e:
        print(f"❌ Dry-run failed with error: {e}", file=sys.stderr)
        sys.exit(1)


def train(args):
    """
    Runs full fine-tuning using Unsloth or Hugging Face Transformers / TRL.
    """
    if args.dry_run:
        run_dry_run(args)
        return

    print(f"==> Starting Qwen 3 Persian Fine-Tuning pipeline for model: {args.model}")

    try:
        import torch
        from transformers import AutoTokenizer
        from trl import SFTTrainer, SFTConfig
    except ImportError as e:
        print(f"! Required training libraries missing: {e}", file=sys.stderr)
        print("Switching to dry-run mode...\n")
        run_dry_run(args)
        return

    # Check Unsloth availability
    has_unsloth = False
    if args.use_unsloth:
        try:
            from unsloth import FastLanguageModel
            from unsloth.chat_templates import get_chat_template, train_on_responses_only
            has_unsloth = True
            print("✓ Unsloth framework detected. Fast training mode enabled!")
        except ImportError:
            print("! Unsloth not installed; using standard HuggingFace PEFT / TRL.")

    # 1. Load Dataset
    dataset = load_persian_dataset(args.dataset, args.dataset_config, args.max_samples)

    # 2. Load Model & Tokenizer
    if has_unsloth:
        model, tokenizer = FastLanguageModel.from_pretrained(
            model_name=args.model,
            max_seq_length=args.max_seq_length,
            dtype=None,
            load_in_4bit=True,
        )

        tokenizer = get_chat_template(
            tokenizer,
            chat_template="qwen-2.5",  # Compatible with Qwen 2.5 & Qwen 3
        )

        model = FastLanguageModel.get_peft_model(
            model,
            r=args.lora_r,
            target_modules=[
                "q_proj",
                "k_proj",
                "v_proj",
                "o_proj",
                "gate_proj",
                "up_proj",
                "down_proj",
            ],
            lora_alpha=args.lora_alpha,
            lora_dropout=0,
            bias="none",
            use_gradient_checkpointing="unsloth",
            random_state=3407,
        )
    else:
        from peft import LoraConfig, get_peft_model
        from transformers import AutoModelForCausalLM

        tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token

        model = AutoModelForCausalLM.from_pretrained(
            args.model,
            torch_dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
            device_map="auto",
            trust_remote_code=True,
        )

        peft_config = LoraConfig(
            r=args.lora_r,
            lora_alpha=args.lora_alpha,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM",
        )
        model = get_peft_model(model, peft_config)

    # 3. Format Dataset with Chat Template
    def formatting_prompts_func(examples):
        # Batch of rows
        if isinstance(examples, dict):
            keys = list(examples.keys())
            num_rows = len(examples[keys[0]])
            texts = []
            for i in range(num_rows):
                row = {k: examples[k][i] for k in keys}
                msgs = normalize_sample_to_messages(row)
                text = tokenizer.apply_chat_template(
                    msgs,
                    tokenize=False,
                    add_generation_prompt=False,
                )
                texts.append(text)
            return {"text": texts}
        return {"text": []}

    if hasattr(dataset, "map"):
        formatted_dataset = dataset.map(formatting_prompts_func, batched=True)
    else:
        formatted_dataset = dataset

    # 4. Trainer Setup
    sft_config = SFTConfig(
        per_device_train_batch_size=args.per_device_batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        num_train_epochs=args.epochs,
        learning_rate=args.learning_rate,
        fp16=not torch.cuda.is_bf16_supported(),
        bf16=torch.cuda.is_bf16_supported(),
        logging_steps=10,
        optim="adamw_8bit" if has_unsloth else "adamw_torch",
        weight_decay=0.01,
        lr_scheduler_type="linear",
        seed=3407,
        output_dir=args.output_dir,
        report_to="none",
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=formatted_dataset,
        dataset_text_field="text",
        max_seq_length=args.max_seq_length,
        dataset_num_proc=2,
        packing=False,
        args=sft_config,
    )

    if has_unsloth:
        trainer = train_on_responses_only(trainer)

    # 5. Train Model
    print("==> Launching training steps...")
    trainer.train()

    # 6. Save Model and Tokenizer
    print(f"==> Saving LoRA adapter and tokenizer to {args.output_dir}...")
    model.save_pretrained(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)

    # 7. Optional GGUF Export for Hushyar App (llama.cpp)
    if args.export_gguf and has_unsloth:
        gguf_dir = os.path.join(args.output_dir, "gguf")
        print(f"==> Exporting model to GGUF format ({args.export_gguf}) at {gguf_dir}...")
        model.save_pretrained_gguf(
            gguf_dir,
            tokenizer,
            quantization_method=args.export_gguf,
        )
        print(f"✓ GGUF model exported successfully to {gguf_dir}")

    print("✓ Training and export completed successfully!")


if __name__ == "__main__":
    cli_args = parse_args()
    if len(sys.argv) == 1:
        cli_args.dry_run = True
    train(cli_args)
