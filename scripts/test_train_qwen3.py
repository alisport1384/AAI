#!/usr/bin/env python3
"""
test_train_qwen3.py — Unit tests for Qwen 3 / Qwen 2.5 Persian fine-tuning script.
"""

import unittest
from scripts.train_qwen3_persian import (
    parse_args,
    load_tiny_persian_dataset,
    format_messages_sample,
    run_dry_run,
)


class TestQwen3TrainingScript(unittest.TestCase):

    def test_parse_args_defaults(self):
        args = parse_args([])
        self.assertEqual(args.model, "Qwen/Qwen2.5-0.5B-Instruct")
        self.assertEqual(args.dataset, "amiralifirouzi/tiny-persian-sft-15k")
        self.assertEqual(args.dataset_config, "fa")
        self.assertEqual(args.max_seq_length, 2048)
        self.assertEqual(args.lora_r, 16)
        self.assertEqual(args.lora_alpha, 16)

    def test_parse_args_custom(self):
        args = parse_args(
            [
                "--model",
                "Qwen/Qwen3-4B-Instruct",
                "--dataset-config",
                "fa_replay",
                "--max-seq-length",
                "1024",
                "--export-gguf",
                "q4_k_m",
                "--dry-run",
            ]
        )
        self.assertEqual(args.model, "Qwen/Qwen3-4B-Instruct")
        self.assertEqual(args.dataset_config, "fa_replay")
        self.assertEqual(args.max_seq_length, 1024)
        self.assertEqual(args.export_gguf, "q4_k_m")
        self.assertTrue(args.dry_run)

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
        data = load_tiny_persian_dataset("amiralifirouzi/tiny-persian-sft-15k", "fa")
        self.assertTrue(len(data) > 0)
        sample = data[0]
        self.assertIn("messages", sample)
        self.assertIn("role", sample["messages"][0])

    def test_run_dry_run_executes(self):
        args = parse_args(["--dry-run"])
        # Should execute without throwing any exception
        run_dry_run(args)


if __name__ == "__main__":
    unittest.main()
