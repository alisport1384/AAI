#!/usr/bin/env python3
"""
train_qwen3_persian.py — Fine-tune Qwen 3 / Qwen 2.5 models on Persian & Multilingual Datasets.

Supported Datasets:
 - nassimjp/Farsi-Reasoning-SFT (Farsi step-by-step reasoning with <think> traces)
 - frasalvi/debategpt (Conversational debate & persuasion dialogues)
 - DebateLabKIT/deep-argmap-conversations (604k structured argument mapping conversations)
 - persiannlp/parsinlu_translation_en_fa (1.67M English to Persian translation pairs)
 - Helsinki-NLP/opus-100 (1M English-Persian parallel translation corpus)
 - amiralifirouzi/jev-data-persian (119k rows, Persian JEV benchmark)
 - amiralifirouzi/Persian-SFT-102K (102k master dataset, ChatML format)
 - xmanii/Persian-Math-SFT (Persian Math SFT)
 - ParsiAI/FarsInstruct (22.7M rows, instruction dataset)
 - amiralifirouzi/tiny-persian-sft-15k (15k ChatML format)
 - Any local JSON / JSONL files

Target Models:
 - Qwen/Qwen3-4B-Instruct
 - Qwen/Qwen2.5-0.5B-Instruct
 - Qwen/Qwen2.5-1.5B-Instruct
 - unsloth/Qwen2.5-1.5B-Instruct

Features:
 - Automatic schema normalization: seamlessly handles 'messages', 'translation', 'source'/'targets', 'inputs'/'outputs', 'question_fa'/'answer_fa', 'question'/'answer', and 'instruction'/'output'.
 - Configurable dataset subsets (e.g. 'en-fa' for opus-100; 'fa', 'fa_replay' for Persian-SFT-102K; 'default', 'pn_sum' for FarsInstruct).
 - Max samples capping (--max-samples) for efficient training on massive multi-million datasets.
 - Official Qwen ChatML template formatting (<|im_start|>role\ncontent<|im_end|>).
 - Loss masking on user instructions (train_on_responses_only).
 - Fast Unsloth QLoRA 4-bit / 16-bit LoRA fine-tuning.
 - Quantized GGUF export (q4_k_m, q8_0) for direct deployment to Hushyar Android app (llama.cpp).
 - Dry-run verification mode.

Usage:
  # Fast dry-run check on Farsi-Reasoning-SFT:
  python3 scripts/train_qwen3_persian.py --dataset nassimjp/Farsi-Reasoning-SFT --dry-run

  # Fast dry-run check on parsinlu_translation_en_fa:
  python3 scripts/train_qwen3_persian.py --dataset persiannlp/parsinlu_translation_en_fa --dry-run

  # Fast dry-run check on opus-100 (en-fa):
  python3 scripts/train_qwen3_persian.py --dataset Helsinki-NLP/opus-100 --dataset-config en-fa --dry-run

  # Full Fine-tuning on Farsi-Reasoning-SFT:
  python3 scripts/train_qwen3_persian.py \
      --model Qwen/Qwen2.5-0.5B-Instruct \
      --dataset nassimjp/Farsi-Reasoning-SFT \
      --output-dir ./qwen3-reasoning-lora \
      --epochs 2 \
      --export-gguf q4_k_m
"""

import argparse
import json
import os
import sys
from pathlib import Path


def parse_args(args=None):
    parser = argparse.ArgumentParser(
        description="Fine-tune Qwen 3 / Qwen 2.5 on Persian & Multilingual Datasets (Farsi-Reasoning-SFT, debategpt, deep-argmap-conversations, parsinlu_translation_en_fa, opus-100, etc.)"
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
        default="default",
        help="Dataset configuration split subset ('default', 'en-fa', 'fa', 'fa_replay', 'pn_sum', etc.)",
    )
    parser.add_argument(
        "--max-samples",
        type=int,
        default=None,
        help="Maximum number of dataset samples to use for training (useful for massive datasets)",
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
    Normalizes different dataset schemas (messages, translation, source/targets, inputs/outputs, question_fa/answer_fa, question/answer, instruction/output)
    to a unified OpenAI ChatML messages list format.
    """
    if not isinstance(sample, dict):
        return []

    # 1. Format: 'messages' column (tiny-persian-sft-15k, Persian-SFT-102K, Farsi-Reasoning-SFT, deep-argmap-conversations, Persian-Math-SFT)
    if "messages" in sample and isinstance(sample["messages"], list) and len(sample["messages"]) > 0:
        return sample["messages"]

    # 2. Format: 'translation' dict (Helsinki-NLP/opus-100 format: {'en': '...', 'fa': '...'})
    if "translation" in sample and isinstance(sample["translation"], dict):
        trans = sample["translation"]
        en_text = str(trans.get("en") or trans.get("source") or "").strip()
        fa_text = str(trans.get("fa") or trans.get("target") or "").strip()
        if en_text and fa_text:
            return [
                {"role": "user", "content": f"Translate the following text to Persian:\n{en_text}"},
                {"role": "assistant", "content": fa_text},
            ]

    # 3. Format: 'source' and 'targets' columns (persiannlp/parsinlu_translation_en_fa format)
    if "source" in sample and ("targets" in sample or "target" in sample):
        src = str(sample["source"] or "").strip()
        targets = sample.get("targets") or sample.get("target")
        tgt = ""
        if isinstance(targets, list) and len(targets) > 0:
            tgt = str(targets[0]).strip()
        elif isinstance(targets, str):
            tgt = targets.strip()

        if src and tgt:
            return [
                {"role": "user", "content": f"Translate the following English text to Persian:\n{src}"},
                {"role": "assistant", "content": tgt},
            ]

    # 4. Format: 'question_fa' and 'answer_fa' columns (amiralifirouzi/jev-data-persian)
    if ("question_fa" in sample or "question" in sample) and ("answer_fa" in sample or "answer" in sample):
        q = str(sample.get("question_fa") or sample.get("question") or "").strip()
        a = str(sample.get("answer_fa") or sample.get("answer") or "").strip()
        state = str(sample.get("state_fa") or sample.get("state") or "").strip()
        options = sample.get("options_fa") or sample.get("options")

        user_parts = []
        if state:
            user_parts.append(state)
        if q:
            user_parts.append(q)
        if isinstance(options, list) and len(options) > 0:
            opts_str = "\n".join(f"{idx+1}. {opt}" for idx, opt in enumerate(options) if opt)
            if opts_str:
                user_parts.append(f"گزینه‌ها:\n{opts_str}")

        user_text = "\n\n".join(user_parts).strip()
        if user_text and a:
            return [
                {"role": "user", "content": user_text},
                {"role": "assistant", "content": a},
            ]

    # 5. Format: 'inputs' and 'outputs' columns (ParsiAI/FarsInstruct)
    if "inputs" in sample and "outputs" in sample:
        inp = str(sample["inputs"] or "").strip()
        out = str(sample["outputs"] or "").strip()
        if inp and out:
            return [
                {"role": "user", "content": inp},
                {"role": "assistant", "content": out},
            ]

    # 6. Format: 'prompt'/'response' or 'text'/'reply' columns (frasalvi/debategpt)
    if ("prompt" in sample or "text" in sample) and ("response" in sample or "reply" in sample):
        p = str(sample.get("prompt") or sample.get("text") or "").strip()
        r = str(sample.get("response") or sample.get("reply") or "").strip()
        if p and r:
            return [
                {"role": "user", "content": p},
                {"role": "assistant", "content": r},
            ]

    # 7. Format: 'question' and 'answer' columns (Persian-Math-SFT fallback)
    if "question" in sample and "answer" in sample:
        q = str(sample["question"] or "").strip()
        a = str(sample["answer"] or "").strip()
        if q and a:
            return [
                {"role": "user", "content": q},
                {"role": "assistant", "content": a},
            ]

    # 8. Format: 'instruction', 'input', 'output' columns (Alpaca / standard instruction)
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
    Loads Persian & Multilingual datasets.
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

        # Config handling for specific datasets
        cfg = None if config in ("default", "all", "none") else config
        if dataset_name_or_path == "Helsinki-NLP/opus-100" and config == "default":
            cfg = "en-fa"
        elif dataset_name_or_path in ("amiralifirouzi/tiny-persian-sft-15k", "amiralifirouzi/Persian-SFT-102K") and config == "default":
            cfg = "fa"

        if config == "all" and dataset_name_or_path in ("amiralifirouzi/tiny-persian-sft-15k", "amiralifirouzi/Persian-SFT-102K"):
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
        ds_lower = dataset_name_or_path.lower()
        if "reasoning" in ds_lower:
            return [
                {
                    "messages": [
                        {"role": "user", "content": "اگر ۳ سیب داشته باشیم و ۲ سیب اضافه کنیم، چند سیب داریم؟"},
                        {"role": "assistant", "content": "<think>\nمحاسبه مجموع: 3 + 2 = 5\n</think>\nمجموعاً ۵ سیب داریم."}
                    ]
                }
            ]
        elif "debategpt" in ds_lower:
            return [
                {
                    "prompt": "Should standardized tests be abolished in schools?",
                    "response": "Standardized testing provides a unified metric, but critics argue it limits creative assessment."
                }
            ]
        elif "argmap" in ds_lower:
            return [
                {
                    "messages": [
                        {"role": "user", "content": "Construct an argument map for renewable energy adoption."},
                        {"role": "assistant", "content": "1. Renewable energy reduces carbon emissions.\n2. High upfront costs require initial subsidies."}
                    ]
                }
            ]
        elif "parsinlu_translation" in ds_lower or "parsinlu" in ds_lower:
            return [
                {
                    "source": "Artificial intelligence is reshaping the modern world.",
                    "targets": ["هوش مصنوعی در حال بازشکل‌دهی جهان مدرن است."],
                    "category": "translation"
                }
            ]
        elif "opus-100" in ds_lower or "opus" in ds_lower:
            return [
                {
                    "translation": {
                        "en": "Knowledge is power.",
                        "fa": "توانا بود هر که دانا بود."
                    }
                }
            ]
        elif "jev" in ds_lower:
            return [
                {
                    "id": "jev-fa-00001",
                    "state_fa": "احمد به بازار رفت تا برای خانه میوه بخرد.",
                    "question_fa": "احمد به چه منظوری به بازار رفت؟",
                    "options_fa": ["خرید میوه", "دیدار با دوست", "خرید لباس"],
                    "answer_fa": "خرید میوه",
                    "source": "jev-benchmark"
                }
            ]
        elif "math" in ds_lower:
            return [
                {
                    "question": "چطور می‌تونم معادله x + 3 = 7 رو حل کنم؟",
                    "answer": "برای حل معادله x + 3 = 7، عدد 3 را به سمت دیگر معادله ببرید: x = 7 - 3 بنابراین x = 4.",
                    "total_tokens": 310,
                    "messages": [
                        {"role": "user", "content": "چطور می‌تونم معادله x + 3 = 7 رو حل کنم؟"},
                        {"role": "assistant", "content": "برای حل معادله x + 3 = 7، عدد 3 را به سمت دیگر معادله ببرید: x = 7 - 3 بنابراین x = 4."}
                    ]
                }
            ]
        elif "102k" in ds_lower:
            return [
                {
                    "id": "master-102k-00001",
                    "source": "persian-sft-master",
                    "task": "reasoning",
                    "messages": [
                        {"role": "user", "content": "مراحل تحلیل یک پروژه استخراج داده به زبان ساده چیست؟"},
                        {"role": "assistant", "content": "تحلیل استخراج داده شامل ۴ مرحله اصلی است: ۱. تعریف مسئله ۲. جمع‌آوری ۳. پالایش داده ۴. مدل‌سازی."}
                    ]
                }
            ]
        elif "farsinstruct" in ds_lower:
            return [
                {
                    "dataset": "pn_summary",
                    "template": "gen_article_with_summary",
                    "inputs": "خلاصه داده شده را با دقت مطالعه کن و آن را در قالب یک مقاله کامل بسط بده",
                    "outputs": "فریده روشن در گفت وگو با خبرنگار ایمنا با اشاره به دستاوردهای نشست..."
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
