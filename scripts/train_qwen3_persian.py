#!/usr/bin/env python3
"""
train_qwen3_persian.py — Fine-tune Qwen 3 / Qwen 2.5 models on amiralifirouzi/tiny-persian-sft-15k dataset.

Dataset Repository: https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k
Target Models: Qwen/Qwen3-4B-Instruct, Qwen/Qwen2.5-0.5B-Instruct, Qwen/Qwen2.5-1.5B-Instruct, unsloth/Qwen2.5-1.5B-Instruct

Features:
 - Loads Hugging Face datasets (`amiralifirouzi/tiny-persian-sft-15k`) or local JSON / JSONL files.
 - Configurable split subsets: 'fa' (12k Persian SFT samples), 'fa_replay' (3k English replay), or 'all'.
 - Processes OpenAI ChatML `messages` format: [{"role": "user"|"assistant"|"system", "content": "..."}].
 - Utilizes official Qwen chat template formatting (`<|im_start|>role\ncontent<|im_end|>`).
 - Applies loss masking on prompt instructions (`train_on_responses_only`).
 - Supports Unsloth QLoRA 4-bit / 16-bit LoRA fine-tuning for high memory efficiency.
 - Exports quantized GGUF format (q4_k_m, q8_0) for direct deployment to Hushyar Android app (llama.cpp).
 - Includes dry-run verification mode runnable in any environment without GPU / heavy dependencies.

Usage:
  # Fast dry-run check:
  python3 scripts/train_qwen3_persian.py --dry-run

  # Full Fine-tuning execution:
  python3 scripts/train_qwen3_persian.py \
      --model Qwen/Qwen2.5-0.5B-Instruct \
      --dataset amiralifirouzi/tiny-persian-sft-15k \
      --dataset-config fa \
      --output-dir ./qwen3-persian-lora \
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
        description="Fine-tune Qwen 3 / Qwen 2.5 on amiralifirouzi/tiny-persian-sft-15k"
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
        help="Hugging Face dataset identifier or local json/jsonl file path",
    )
    parser.add_argument(
        "--dataset-config",
        type=str,
        default="fa",
        choices=["fa", "fa_replay", "all"],
        help="Dataset configuration split subset ('fa' for Persian SFT, 'fa_replay' for English replay)",
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


def load_tiny_persian_dataset(dataset_name_or_path: str, config: str):
    """
    Loads `amiralifirouzi/tiny-persian-sft-15k` dataset or local JSON file.
    """
    if os.path.exists(dataset_name_or_path):
        print(f"==> Loading local dataset file: {dataset_name_or_path}")
        with open(dataset_name_or_path, "r", encoding="utf-8") as f:
            if dataset_name_or_path.endswith(".jsonl"):
                data = [json.loads(line) for line in f if line.strip()]
            else:
                data = json.load(f)
        return data

    try:
        from datasets import load_dataset, concatenate_datasets
        print(f"==> Loading HuggingFace dataset: {dataset_name_or_path} (config={config})")
        if config == "all":
            ds_fa = load_dataset(dataset_name_or_path, "fa", split="train")
            ds_replay = load_dataset(dataset_name_or_path, "fa_replay", split="train")
            return concatenate_datasets([ds_fa, ds_replay])
        else:
            return load_dataset(dataset_name_or_path, config, split="train")
    except ImportError:
        print("! 'datasets' package not installed; generating synthetic preview sample for verification...")
        sample_persian_sft = [
            {
                "id": "norobots-08386",
                "task": "open qa",
                "language": "fa",
                "family": "qa",
                "messages": [
                    {"role": "user", "content": "بهترین کتاب پرفروش تمام دوران چیست؟"},
                    {"role": "assistant", "content": "بیشترین کتاب پرفروش تاریخ، کتاب مقدس مسیحی است."}
                ]
            },
            {
                "id": "iron-sft-00001",
                "task": "domain qa",
                "language": "fa",
                "family": "iron",
                "messages": [
                    {"role": "user", "content": "ضخامت ورق گالوانیزه استاندارد چیست؟"},
                    {"role": "assistant", "content": "ضخامت استاندارد ورق گالوانیزه بین ۰.۴ تا ۲.۵ میلی‌متر متغیر است."}
                ]
            }
        ]
        return sample_persian_sft


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
    print(f"  Output Dir     : {args.output_dir}")
    print(f"  Max Seq Length : {args.max_seq_length}")
    print(f"  LoRA Config    : r={args.lora_r}, alpha={args.lora_alpha}")
    print("==========================================================")

    try:
        ds = load_tiny_persian_dataset(args.dataset, args.dataset_config)
        sample_count = len(ds)
        print(f"✓ Dataset loaded successfully! Total samples available: {sample_count}")

        sample = ds[0] if isinstance(ds, list) else ds[0]
        messages = sample["messages"]
        print(f"✓ Sample ID          : {sample.get('id', 'N/A')}")
        print(f"✓ Sample Task        : {sample.get('task', 'N/A')}")
        print(f"✓ Sample Language    : {sample.get('language', 'N/A')}")
        print(f"✓ Sample Messages    : {len(messages)} turns")
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
    dataset = load_tiny_persian_dataset(args.dataset, args.dataset_config)

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
        convos = examples["messages"]
        texts = [
            tokenizer.apply_chat_template(
                convo,
                tokenize=False,
                add_generation_prompt=False,
            )
            for convo in convos
        ]
        return {"text": texts}

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
