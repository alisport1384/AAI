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

    def test_normalize_aquila_sft_schema(self):
        aquila_sample = {
            "question": "Search for Python libraries.",
            "answer": "NumPy and Pandas are popular.",
            "number of tool calls": 1,
            "trajectory": [
                {"role": "user", "content": "Search for Python libraries."},
                {"role": "assistant", "content": "NumPy and Pandas are popular."}
            ]
        }
        messages = normalize_sample_to_messages(aquila_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")
        self.assertEqual(messages[0]["content"], "Search for Python libraries.")
        self.assertEqual(messages[1]["role"], "assistant")
        self.assertEqual(messages[1]["content"], "NumPy and Pandas are popular.")

    def test_normalize_translation_schemas(self):
        # 1. parsinlu_translation_en_fa
        parsinlu_sample = {
            "source": "Machine learning is fascinating.",
            "targets": ["یادگیری ماشین جذاب است."],
            "category": "translation",
        }
        msgs1 = normalize_sample_to_messages(parsinlu_sample)
        self.assertEqual(len(msgs1), 2)
        self.assertEqual(msgs1[0]["role"], "user")
        self.assertIn("Translate the following English text to Persian:", msgs1[0]["content"])

        # 2. opus-100
        opus_sample = {
            "translation": {
                "en": "Where is the library?",
                "fa": "کتابخانه کجاست؟",
            }
        }
        msgs2 = normalize_sample_to_messages(opus_sample)
        self.assertEqual(len(msgs2), 2)
        self.assertEqual(msgs2[1]["content"], "کتابخانه کجاست؟")

    def test_normalize_debategpt_schema(self):
        debate_sample = {
            "prompt": "Is space exploration worth the cost?",
            "response": "Space exploration drives technological innovation.",
        }
        messages = normalize_sample_to_messages(debate_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")

    def test_normalize_jev_schema(self):
        jev_sample = {
            "state_fa": "تیم ملی ایران پیروز شد.",
            "question_fa": "کدام تیم پیروز شد؟",
            "options_fa": ["ایران", "ژاپن"],
            "answer_fa": "ایران",
        }
        messages = normalize_sample_to_messages(jev_sample)
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[0]["role"], "user")

    def test_run_dry_run_all_datasets(self):
        datasets_to_test = [
            "XYZAILab/XYZ-Aquila-SFT",
            "nassimjp/Farsi-Reasoning-SFT",
            "frasalvi/debategpt",
            "DebateLabKIT/deep-argmap-conversations",
            "persiannlp/parsinlu_translation_en_fa",
            "Helsinki-NLP/opus-100",
            "amiralifirouzi/jev-data-persian",
            "amiralifirouzi/Persian-SFT-102K",
            "xmanii/Persian-Math-SFT",
            "ParsiAI/FarsInstruct",
            "amiralifirouzi/tiny-persian-sft-15k",
        ]
        for ds_name in datasets_to_test:
            args = parse_args(["--dataset", ds_name, "--dry-run"])
            run_dry_run(args)


if __name__ == "__main__":
    unittest.main()
