#!/usr/bin/env python3
"""
test_train_qwen3.py — Unit tests for Qwen 3 / Qwen 2.5 Persian fine-tuning script with multi-dataset support.
"""

import unittest
from scripts.train_qwen3_persian import (
    parse_args,
    load_persian_dataset,
    format_messages_sample,
    normalize_sample_to_messages,
    run_dry_run,
)


class TestQwen3TrainingScript(unittest.TestCase):

    def test_parse_args_defaults(self):
        args = parse_args([])
        self.assertEqual(args.model, "Qwen/Qwen2.5-0.5B-Instruct")
        self.assertEqual(args.dataset, "amiralifirouzi/tiny-persian-sft-15k")
        self.assertEqual(args.dataset_config, "default")
        self.assertEqual(args.max_seq_length, 2048)
        self.assertEqual(args.lora_r, 16)
        self.assertEqual(args.lora_alpha, 16)

    def test_parse_args_farsinstruct(self):
        args = parse_args(
            [
                "--model",
                "Qwen/Qwen3-4B-Instruct",
                "--dataset",
                "ParsiAI/FarsInstruct",
                "--dataset-config",
                "pn_sum",
                "--max-samples",
                "10000",
                "--export-gguf",
                "q4_k_m",
                "--dry-run",
            ]
        )
        self.assertEqual(args.model, "Qwen/Qwen3-4B-Instruct")
        self.assertEqual(args.dataset, "ParsiAI/FarsInstruct")
        self.assertEqual(args.dataset_config, "pn_sum")
        self.assertEqual(args.max_samples, 10000)
        self.assertEqual(args.export_gguf, "q4_k_m")
        self.assertTrue(args.dry_run)

    def test_normalize_farsinstruct_schema(self):
        farsinstruct_sample = {
            "dataset": "pn_summary",
            "template": "gen_article_with_summary",
            "inputs": "متن را خلاصه کن",
            "outputs": "این یک خلاصه است.",
        }
        messages = normalize_sample_to_messages(farsinstruct_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")
        self.assertEqual(messages[0]["content"], "متن را خلاصه کن")
        self.assertEqual(messages[1]["role"], "assistant")
        self.assertEqual(messages[1]["content"], "این یک خلاصه است.")

    def test_normalize_tiny_persian_schema(self):
        tiny_sample = {
            "id": "norobots-001",
            "messages": [
                {"role": "user", "content": "سلام"},
                {"role": "assistant", "content": "درود"},
            ],
        }
        messages = normalize_sample_to_messages(tiny_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")

    def test_format_messages_sample(self):
        messages = [
            {"role": "user", "content": "سلام"},
            {"role": "assistant", "content": "درود! چطور می‌توانم کمکتان کنم؟"},
        ]
        formatted = format_messages_sample(messages)
        self.assertIn("<|im_start|>user\nسلام<|im_end|>", formatted)
        self.assertIn(
            "<|im_start|>assistant\nدرود! چطور می‌توانم کمکتان کنم؟<|im_end|>",
            formatted,
        )

    def test_load_dataset_fallback(self):
        data = load_persian_dataset("ParsiAI/FarsInstruct", "default")
        self.assertTrue(len(data) > 0)
        sample = data[0]
        messages = normalize_sample_to_messages(sample)
        self.assertTrue(len(messages) > 0)

    def test_run_dry_run_executes(self):
        args = parse_args(["--dataset", "ParsiAI/FarsInstruct", "--dry-run"])
        run_dry_run(args)


if __name__ == "__main__":
    unittest.main()
