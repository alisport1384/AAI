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

    def test_parse_args_custom_datasets(self):
        args_102k = parse_args(
            [
                "--model",
                "Qwen/Qwen3-4B-Instruct",
                "--dataset",
                "amiralifirouzi/Persian-SFT-102K",
                "--dataset-config",
                "fa",
                "--export-gguf",
                "q4_k_m",
                "--dry-run",
            ]
        )
        self.assertEqual(args_102k.dataset, "amiralifirouzi/Persian-SFT-102K")

        args_math = parse_args(
            [
                "--model",
                "Qwen/Qwen2.5-0.5B-Instruct",
                "--dataset",
                "xmanii/Persian-Math-SFT",
                "--export-gguf",
                "q8_0",
                "--dry-run",
            ]
        )
        self.assertEqual(args_math.dataset, "xmanii/Persian-Math-SFT")

    def test_normalize_math_sft_schema(self):
        math_sample = {
            "question": "معادله 2x = 8 چطور حل میشه؟",
            "answer": "طرفین را بر 2 تقسیم کنید: x = 4.",
            "total_tokens": 45,
        }
        messages = normalize_sample_to_messages(math_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")
        self.assertEqual(messages[0]["content"], "معادله 2x = 8 چطور حل میشه؟")
        self.assertEqual(messages[1]["role"], "assistant")
        self.assertEqual(messages[1]["content"], "طرفین را بر 2 تقسیم کنید: x = 4.")

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

    def test_normalize_tiny_and_102k_schema(self):
        sample = {
            "id": "norobots-001",
            "messages": [
                {"role": "user", "content": "سلام"},
                {"role": "assistant", "content": "درود"},
            ],
        }
        messages = normalize_sample_to_messages(sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")

    def test_format_messages_sample(self):
        messages = [
            {"role": "user", "content": "سلام"},
            {"role": "assistant", "content": "درود! چطور می‌توانم کمکتان کنم؟"},
        ]
        formatted = format_messages_sample(messages)
        self.assertIn("<|im_start|>user\nسلام<|im_end|>", formatted)

    def test_run_dry_run_all_datasets(self):
        for ds_name in [
            "amiralifirouzi/tiny-persian-sft-15k",
            "amiralifirouzi/Persian-SFT-102K",
            "xmanii/Persian-Math-SFT",
            "ParsiAI/FarsInstruct",
        ]:
            args = parse_args(["--dataset", ds_name, "--dry-run"])
            run_dry_run(args)


if __name__ == "__main__":
    unittest.main()
