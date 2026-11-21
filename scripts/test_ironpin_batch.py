#!/usr/bin/env python3
"""
test_ironpin_batch.py — Unit tests for IronPin Master Batch schema and generator.
"""

import json
import unittest
from pathlib import Path
from scripts.generate_ironpin_batch import generate_ironpin_master_batch


class TestIronPinMasterBatch(unittest.TestCase):

    def setUp(self):
        self.asset_path = Path(__file__).resolve().parent.parent / "app/src/main/assets/ironpin_batch_master.json"

    def test_generator_runs_and_writes_file(self):
        out_path = generate_ironpin_master_batch()
        self.assertTrue(out_path.exists())

    def test_batch_json_structure(self):
        with open(self.asset_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.assertEqual(data.get("format"), "ironpin-batch-v1")
        self.assertIn("batch", data)
        self.assertIn("items", data)
        items = data["items"]
        self.assertTrue(len(items) >= 12)

        # Verify essential IronPin schema fields per item
        required_fields = [
            "id", "family", "title", "seller", "seller_city",
            "material", "unit", "amount", "currency", "source_url"
        ]

        families_found = set()
        for item in items:
            for field in required_fields:
                self.assertIn(field, item, f"Missing {field} in item {item.get('id')}")

            families_found.add(item["family"])
            self.assertEqual(item["currency"], "تومان")
            self.assertTrue(item["amount"] > 0)
            self.assertTrue(item["source_url"].startswith("http"))

        # Verify key steel families are present
        expected_families = {"ورق", "میلگرد", "تیرآهن", "نبشی", "ناودانی", "پروفیل", "لوله", "بولت"}
        for fam in expected_families:
            self.assertIn(fam, families_found, f"Expected family {fam} not found in batch!")


if __name__ == "__main__":
    unittest.main()
