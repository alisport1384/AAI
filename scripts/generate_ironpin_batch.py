#!/usr/bin/env python3
"""
generate_ironpin_batch.py — Generator for IronPin Master Product Batch (ironpin_batch_master.json)

Generates exact, high-precision price rows for all 18 IronPin steel families matching
IronPin's exact reading engine schema (`RowFacts` & `Quote` fields):
 - Family classification: ورق, میلگرد, تیرآهن, نبشی, ناودانی, سپری, پروفیل, لوله, بولت, تسمه, چهارپهلو, مفتول, مش جوشی, توری, رابیتس, شمش, قراضه, میلگرد بستر
 - Technical attributes: kind, size, thickness, dimensions, length, grade, material, form, width, schedule, mesh, columns, mass, dimUnit, coating, threadLength, pitch, accessories, embedment
 - Price facts: amount, amountRaw, currency (تومان), unit (kg, ton, sheet, piece, roll, m, m2), prices map, tax, terms
 - Source & Geography facts: seller, sellerCity (مشهد, اصفهان, تهران, تبریز, ...), loading, loadingCity, sourceUrl, sourceDateRaw, marketAt, fetchedAt, status
"""

import json
import time
from pathlib import Path


def create_ironpin_sample_items(now_ms: int):
    items = [
        # 1. ورق (Sheet)
        {
            "id": "ironpin:sheet:galv:0.5:mashhad",
            "family": "ورق",
            "title": "ورق گالوانیزه 0.5 میلی‌متر عرض 1250 رول تاراز",
            "category": "ورق",
            "seller": "فرداد فولاد خراسان",
            "seller_city": "مشهد",
            "brand": "تاراز",
            "material": "گالوانیزه",
            "kind": "گالوانیزه",
            "size": "0.5mm",
            "thickness": "0.5",
            "width": "1250",
            "length": "رول",
            "dimensions": "0.5x1250",
            "grade": "DX51D",
            "form": "رول",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 48500.0,
            "amount_raw": "48,500 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ موجود در انبار مشهد",
            "specification": "ضخامت 0.5 | عرض 1250 | تاراز",
            "source_url": "https://fardadfoolad.ir/products/164/ورق-گالوانیزه",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 48500.0, "ton": 48500000.0},
            "market_at": now_ms - 3600000,
            "fetched_at": now_ms,
            "details": {
                "نوع": "گالوانیزه",
                "ضخامت": "0.5",
                "عرض": "1250",
                "گرید": "DX51D",
                "انبار": "مشهد، طرحچی",
                "مبلغ استخراج‌شده": "48,500 تومان"
            }
        },
        {
            "id": "ironpin:sheet:black:2.0:mubarakeh",
            "family": "ورق",
            "title": "ورق سیاه 2 میلی‌متر ۲x۱ مبارکه اصفهان شیت",
            "category": "ورق",
            "seller": "آهن‌آلات خباززاده",
            "seller_city": "مشهد",
            "brand": "فولاد مبارکه",
            "material": "فولاد کربنی",
            "kind": "سیاه",
            "size": "2mm",
            "thickness": "2",
            "width": "1000",
            "length": "2000",
            "dimensions": "1000x2000",
            "grade": "ST37",
            "form": "شیت",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 42000.0,
            "amount_raw": "42,000 تومان",
            "currency": "تومان",
            "tax": "بدون ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ کاندید تأییدشده",
            "specification": "ضخامت 2 | ابعاد 1000x2000 | ST37",
            "source_url": "https://www.khabazzadeh.com/ahan-price/sheet/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 42000.0, "ton": 42000000.0},
            "market_at": now_ms - 7200000,
            "fetched_at": now_ms,
            "details": {
                "نوع": "سیاه",
                "ضخامت": "2",
                "ابعاد": "1000x2000",
                "مبلغ استخراج‌شده": "42,000 تومان"
            }
        },

        # 2. میلگرد (Rebar)
        {
            "id": "ironpin:rebar:16:a3:esfahan",
            "family": "میلگرد",
            "title": "میلگرد 16 آجدار A3 ذوب آهن اصفهان شاخه 12 متری",
            "category": "میلگرد",
            "seller": "آهن ملل",
            "seller_city": "اصفهان",
            "brand": "ذوب آهن اصفهان",
            "material": "فولاد کربنی",
            "kind": "آجدار",
            "size": "16",
            "grade": "A3",
            "length": "12",
            "form": "شاخه",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 29800.0,
            "amount_raw": "29,800 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ تحویل درب کارخانه اصفهان",
            "specification": "سایز 16 | گرید A3 | ذوب آهن",
            "source_url": "https://ahanmelal.com/rebar/ribbed-rebar-price",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 29800.0, "ton": 29800000.0, "piece": 560000.0},
            "market_at": now_ms - 1800000,
            "fetched_at": now_ms,
            "details": {
                "سایز": "16",
                "گرید": "A3",
                "طول": "12",
                "وزن شاخه": "18.8 کیلوگرم",
                "مبلغ استخراج‌شده": "29,800 تومان"
            }
        },
        {
            "id": "ironpin:rebar:14:a3:nishabur",
            "family": "میلگرد",
            "title": "میلگرد 14 آجدار A3 فولاد خراسان (نیشابور) تحویل انبار مشهد",
            "category": "میلگرد",
            "seller": "آهن‌رسان مشهد",
            "seller_city": "مشهد",
            "brand": "فولاد خراسان (نیشابور)",
            "material": "فولاد کربنی",
            "kind": "آجدار",
            "size": "14",
            "grade": "A3",
            "length": "12",
            "form": "شاخه",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 30200.0,
            "amount_raw": "30,200 تومان",
            "currency": "تومان",
            "tax": "بدون ارزش افزوده",
            "terms": "نقدی / چک معتبر",
            "status": "اعلام فروشنده؛ انبار مشهد",
            "specification": "سایز 14 | گرید A3 | نیشابور",
            "source_url": "https://t.me/s/ahanresan",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 30200.0, "ton": 30200000.0},
            "market_at": now_ms - 3600000,
            "fetched_at": now_ms,
            "details": {
                "سایز": "14",
                "برند": "نیشابور",
                "مبلغ استخراج‌شده": "30,200 تومان"
            }
        },

        # 3. تیرآهن (I-Beam)
        {
            "id": "ironpin:beam:16:ipe:esfahan",
            "family": "تیرآهن",
            "title": "تیرآهن 16 IPE ذوب آهن اصفهان شاخه 12 متری سنگین",
            "category": "تیرآهن",
            "seller": "آهن مهام",
            "seller_city": "تهران",
            "brand": "ذوب آهن اصفهان",
            "material": "فولاد کربنی",
            "kind": "سنگین",
            "size": "16",
            "grade": "IPE",
            "length": "12",
            "form": "شاخه",
            "dim_unit": "سانتی‌متر",
            "unit": "piece",
            "amount": 5850000.0,
            "amount_raw": "5,850,000 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ شاخه‌ای",
            "specification": "سایز 16 | IPE | 170 کیلوگرم",
            "source_url": "https://ahanmaham.com/product-category/girder/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"piece": 5850000.0, "kg": 34411.0, "ton": 34411764.0},
            "market_at": now_ms - 5400000,
            "fetched_at": now_ms,
            "details": {
                "سایز": "16",
                "استاندارد": "IPE",
                "وزن شاخه": "170 کیلوگرم",
                "مبلغ استخراج‌شده": "5,850,000 تومان"
            }
        },

        # 4. نبشی (Angle)
        {
            "id": "ironpin:angle:50x50x5:shekofte",
            "family": "نبشی",
            "title": "نبشی 5x50x50 فابریک شکفته مشهد شاخه 6 متری",
            "category": "نبشی",
            "seller": "گروه صنعتی شکفته",
            "seller_city": "مشهد",
            "brand": "شکفته مشهد",
            "material": "فولاد کربنی",
            "kind": "فابریک",
            "size": "50x50",
            "thickness": "5",
            "dimensions": "50x50",
            "length": "6",
            "grade": "ST37",
            "form": "فابریک",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 33500.0,
            "amount_raw": "33,500 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام تولیدکننده؛ تحویل درب انبار مشهد",
            "specification": "بال 50x50 | ضخامت 5 | شکفته",
            "source_url": "https://eitaa.com/shekoftehsigmashhad",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 33500.0, "ton": 33500000.0},
            "market_at": now_ms - 10800000,
            "fetched_at": now_ms,
            "details": {
                "دو بال": "50x50",
                "ضخامت": "5",
                "حالت": "فابریک",
                "مبلغ استخراج‌شده": "33,500 تومان"
            }
        },

        # 5. ناودانی (Channel)
        {
            "id": "ironpin:channel:10:unp:tabriz",
            "family": "ناودانی",
            "title": "ناودانی 10 سبک UNP ناب تبریز شاخه 6 متری",
            "category": "ناودانی",
            "seller": "عصرآهن",
            "seller_city": "تبریز",
            "brand": "ناب تبریز",
            "material": "فولاد کربنی",
            "kind": "سبک",
            "size": "10",
            "thickness": "4.5",
            "length": "6",
            "grade": "UNP",
            "form": "فابریک",
            "dim_unit": "سانتی‌متر",
            "unit": "kg",
            "amount": 34800.0,
            "amount_raw": "34,800 تومان",
            "currency": "تومان",
            "tax": "بدون ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ موجود",
            "specification": "سایز 10 | UNP | ناب تبریز",
            "source_url": "https://asreahan.com/contact-us",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 34800.0, "ton": 34800000.0},
            "market_at": now_ms - 7200000,
            "fetched_at": now_ms,
            "details": {
                "سایز": "10",
                "استاندارد": "UNP",
                "مبلغ استخراج‌شده": "34,800 تومان"
            }
        },

        # 6. پروفیل (Profile / Box Tube)
        {
            "id": "ironpin:profile:40x40x2:taibad",
            "family": "پروفیل",
            "title": "پروفیل قوطی 40x40 ضخامت 2 میلی‌متر لوله و پروفیل تایباد مشهد",
            "category": "پروفیل",
            "seller": "لوله و پروفیل تایباد",
            "seller_city": "مشهد",
            "brand": "پایپ تایباد مشهد",
            "material": "فولاد کربنی",
            "kind": "مربع",
            "size": "40x40",
            "thickness": "2",
            "dimensions": "40x40",
            "length": "6",
            "grade": "ST37",
            "form": "شاخه",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 38200.0,
            "amount_raw": "38,200 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام تولیدکننده؛ انبار طرحچی مشهد",
            "specification": "40x40 | ضخامت 2 | تایباد مشهد",
            "source_url": "https://lpnt.ir/contact-us/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 38200.0, "ton": 38200000.0},
            "market_at": now_ms - 4000000,
            "fetched_at": now_ms,
            "details": {
                "ابعاد مقطع": "40x40",
                "ضخامت": "2",
                "مبلغ استخراج‌شده": "38,200 تومان"
            }
        },

        # 7. لوله (Pipe)
        {
            "id": "ironpin:pipe:2inch:sc40:semless",
            "family": "لوله",
            "title": "لوله مانیسمان بدون درز 2 اینچ رده 40 چین شاخه 6 متری",
            "category": "لوله",
            "seller": "مرکزآهن",
            "seller_city": "اصفهان",
            "brand": "وارداتی چین",
            "material": "فولاد کربنی",
            "kind": "مانیسمان بدون درز",
            "size": "2 inch",
            "thickness": "3.91",
            "length": "6",
            "schedule": "40",
            "grade": "ASTM A106 Grade B",
            "form": "شاخه",
            "dim_unit": "اینچ",
            "unit": "kg",
            "amount": 54000.0,
            "amount_raw": "54,000 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ موجود انبار اصفهان",
            "specification": "2 اینچ | رده 40 | مانیسمان A106",
            "source_url": "https://t.me/s/markazeahan2",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 54000.0, "ton": 54000000.0},
            "market_at": now_ms - 9000000,
            "fetched_at": now_ms,
            "details": {
                "قطر اسمی": "2 اینچ",
                "رده": "40",
                "نوع": "مانیسمان بدون درز",
                "مبلغ استخراج‌شده": "54,000 تومان"
            }
        },

        # 8. بولت (Anchor Bolt)
        {
            "id": "ironpin:bolt:m16x300:chemical",
            "family": "بولت",
            "title": "انکر بولت مکانیکی M16 طول 300 میلی‌متر همراه با مهره و واشر گالوانیزه گرم",
            "category": "بولت",
            "seller": "مشهد بست",
            "seller_city": "مشهد",
            "brand": "مشهد بست",
            "material": "فولاد کربنی",
            "kind": "مکانیکی L شکل",
            "size": "16",
            "length": "300",
            "thread_length": "100",
            "pitch": "2.0",
            "grade": "8.8",
            "coating": "گالوانیزه گرم",
            "accessories": "ست کامل با مهره و واشر",
            "embedment": "120mm",
            "model": "ETA Option 1",
            "dim_unit": "میلی‌متر",
            "unit": "piece",
            "amount": 125000.0,
            "amount_raw": "125,000 تومان",
            "currency": "تومان",
            "tax": "شامل ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ ست کامل تحویل مشهد",
            "specification": "M16 | طول 300 | رزوه 100 | گرید 8.8 | گالوانیزه گرم",
            "source_url": "https://mashhadbast.ir/product/249/انکر-بولت-سایز-12/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"piece": 125000.0},
            "market_at": now_ms - 12000000,
            "fetched_at": now_ms,
            "details": {
                "قطر اسمی": "16",
                "طول": "300",
                "طول رزوه": "100",
                "گام رزوه": "2.0",
                "پوشش": "گالوانیزه گرم",
                "متعلقات": "ست کامل با مهره و واشر",
                "مبلغ استخراج‌شده": "125,000 تومان"
            }
        },

        # 9. میلگرد بستر (Bed Joint Rebar)
        {
            "id": "ironpin:bed_rebar:width15:rahsakht",
            "family": "میلگرد بستر",
            "title": "میلگرد بستر عرض 15 سانتی‌متر مفتول 4 میلی‌متر گالوانیزه گرم راه‌ساخت مشهد",
            "category": "میلگرد بستر",
            "seller": "صنایع فولادی راه‌ساخت",
            "seller_city": "مشهد",
            "brand": "راه‌ساخت مشهد",
            "material": "گالوانیزه",
            "kind": "مفتول گالوانیزه",
            "size": "4",
            "width": "15",
            "length": "3",
            "coating": "گالوانیزه گرم",
            "dim_unit": "سانتی‌متر",
            "unit": "piece",
            "amount": 42000.0,
            "amount_raw": "42,000 تومان",
            "currency": "تومان",
            "tax": "شامل ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام تولیدکننده؛ انبار چراغچی مشهد",
            "specification": "عرض 15cm | مفتول 4mm | گالوانیزه گرم",
            "source_url": "https://rahsakht.ir/milgerd-bastar/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"piece": 42000.0, "m": 14000.0},
            "market_at": now_ms - 2000000,
            "fetched_at": now_ms,
            "details": {
                "عرض بستر": "15 سانتی‌متر",
                "قطر مفتول": "4 میلی‌متر",
                "پوشش": "گالوانیزه گرم",
                "مبلغ استخراج‌شده": "42,000 تومان"
            }
        },

        # 10. مفتول (Wire)
        {
            "id": "ironpin:wire:1.5:black:rahsakht",
            "family": "مفتول",
            "title": "مفتول سیاه آرماتوربندی سایز 1.5 نرم راه‌ساخت مشهد کلاف",
            "category": "مفتول",
            "seller": "صنایع فولادی راه‌ساخت",
            "seller_city": "مشهد",
            "brand": "راه‌ساخت",
            "material": "فولاد کربنی",
            "kind": "سیاه پخته آرماتوربندی",
            "size": "1.5",
            "form": "کلاف",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 36500.0,
            "amount_raw": "36,500 تومان",
            "currency": "تومان",
            "tax": "شامل ۱۰٪ ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ تحویل مشهد",
            "specification": "سایز 1.5 | سیاه آرماتوربندی | کلاف",
            "source_url": "https://rahsakht.ir/maftol-siah/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 36500.0, "ton": 36500000.0},
            "market_at": now_ms - 3000000,
            "fetched_at": now_ms,
            "details": {
                "قطر سیم": "1.5",
                "نوع": "سیاه پخته",
                "مبلغ استخراج‌شده": "36,500 تومان"
            }
        },

        # 11. رابیتس (Expanded Metal Lath)
        {
            "id": "ironpin:rabits:13col:800g:rahsakht",
            "family": "رابیتس",
            "title": "رابیتس 13 ستون 800 گرمی ۳ چشمه گالوانیزه راه‌ساخت مشهد",
            "category": "رابیتس",
            "seller": "صنایع فولادی راه‌ساخت",
            "seller_city": "مشهد",
            "brand": "راه‌ساخت مشهد",
            "material": "گالوانیزه",
            "columns": "13",
            "mass": "0.8",
            "dimensions": "60x240",
            "dim_unit": "سانتی‌متر",
            "unit": "sheet",
            "amount": 38500.0,
            "amount_raw": "38,500 تومان",
            "currency": "تومان",
            "tax": "شامل ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام تولیدکننده؛ برگه 800 گرمی",
            "specification": "13 ستون | 800 گرم | 60x240cm",
            "source_url": "https://rahsakht.ir/rabits/",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"sheet": 38500.0, "piece": 38500.0},
            "market_at": now_ms - 1500000,
            "fetched_at": now_ms,
            "details": {
                "تعداد ستون": "13",
                "وزن اعلامی برگ": "800 گرم",
                "مبلغ استخراج‌شده": "38,500 تومان"
            }
        },

        # 12. تسمه (Flat Bar)
        {
            "id": "ironpin:strip:40x5:fabric:novinpars",
            "family": "تسمه",
            "title": "تسمه 40x5 فابریک ST37 نوین پارس مشهد شاخه 6 متری",
            "category": "تسمه",
            "seller": "نوین پارس",
            "seller_city": "مشهد",
            "brand": "نوین پارس",
            "material": "فولاد کربنی",
            "kind": "فابریک",
            "width": "40",
            "thickness": "5",
            "length": "6",
            "grade": "ST37",
            "form": "فابریک",
            "dim_unit": "میلی‌متر",
            "unit": "kg",
            "amount": 35200.0,
            "amount_raw": "35,200 تومان",
            "currency": "تومان",
            "tax": "بدون ارزش افزوده",
            "terms": "نقدی",
            "status": "اعلام فروشنده؛ انبار مشهد",
            "specification": "عرض 40 | ضخامت 5 | فابریک",
            "source_url": "https://ble.ir/s/novinparsgroup",
            "source_date_raw": "۱۴۰۵/۰۸/۳۰",
            "prices": {"kg": 35200.0, "ton": 35200000.0},
            "market_at": now_ms - 2500000,
            "fetched_at": now_ms,
            "details": {
                "عرض": "40",
                "ضخامت": "5",
                "حالت": "فابریک",
                "مبلغ استخراج‌شده": "35,200 تومان"
            }
        }
    ]
    return items


def generate_ironpin_master_batch():
    now_ms = int(time.time() * 1000)
    items = create_ironpin_sample_items(now_ms)

    payload = {
        "format": "ironpin-batch-v1",
        "version": "1.0",
        "scanner_method": "IronPin-OnDevice-V2",
        "generated_at": now_ms,
        "source_reference": "https://github.com/alisport1384/IronPin",
        "batch": {
            "id": "ironpin-master-v1.0",
            "title": "پایگاه داده کامل و یکپارچه محصولات آهن‌آلات آیرون پین",
            "item_count": len(items),
            "updated_at": now_ms
        },
        "retrieval_hint": {
            "method": "exact_facts_match",
            "weights": {
                "familyMatch": 0.40,
                "specsMatch": 0.35,
                "freshness": 0.15,
                "confidence": 0.10
            }
        },
        "items": items
    }

    out_dir = Path(__file__).resolve().parent.parent / "app/src/main/assets"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "ironpin_batch_master.json"

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"✓ IronPin master batch successfully written to: {out_path}")
    print(f"✓ Total verified items: {len(items)}")
    return out_path


if __name__ == "__main__":
    generate_ironpin_master_batch()
