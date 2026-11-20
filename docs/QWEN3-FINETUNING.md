# راهنمای کامل آموزش و فاین‌تیون کوئن ۳ (Qwen 3) روی دیتاست‌های جامع فارسی، چندزبانه و عامل هوشمند (Agent)

> **نسخه:** ۱.۵ — ۳۰ آبان ۱۴۰۵ (2026-11-20) — **پروژه:** AAI v0.16.1
> **دیتاست‌های پشتیبانی‌شده:**
>  1. [`XYZAILab/XYZ-Aquila-SFT`](https://huggingface.co/datasets/XYZAILab/XYZ-Aquila-SFT) (۷,۰۰۰ مسیر چندنوبتی فراخوانی ابزار و جستجوی عامل هوشمند به انگلیسی و چینی)
>  2. [`nassimjp/Farsi-Reasoning-SFT`](https://huggingface.co/datasets/nassimjp/Farsi-Reasoning-SFT) (استدلال گام‌به‌گام فارسی با تگ‌های `<think>`)
>  3. [`frasalvi/debategpt`](https://huggingface.co/datasets/frasalvi/debategpt) (دیتاست گفتگوهای مناظره و استدلال اقناعی)
>  4. [`DebateLabKIT/deep-argmap-conversations`](https://huggingface.co/datasets/DebateLabKIT/deep-argmap-conversations) (۶۰۴ هزار گفتگو برای ساخت گراف استدلال)
>  5. [`persiannlp/parsinlu_translation_en_fa`](https://huggingface.co/datasets/persiannlp/parsinlu_translation_en_fa) (۱.۶۷ میلیون زوج جمله ترجمه انگلیسی به فارسی)
>  6. [`Helsinki-NLP/opus-100`](https://huggingface.co/datasets/Helsinki-NLP/opus-100) (۱ میلیون زوج جمله ترجمه پیکره عمومی انگلیسی-فارسی)
>  7. [`amiralifirouzi/jev-data-persian`](https://huggingface.co/datasets/amiralifirouzi/jev-data-persian) (۱۱۹ هزار رکورد استدلال JEV)
>  8. [`amiralifirouzi/Persian-SFT-102K`](https://huggingface.co/datasets/amiralifirouzi/Persian-SFT-102K) (۱۰۲ هزار رکورد جامع SFT)
>  9. [`xmanii/Persian-Math-SFT`](https://huggingface.co/datasets/xmanii/Persian-Math-SFT) (استدلال ریاضی)
> 10. [`ParsiAI/FarsInstruct`](https://huggingface.co/datasets/ParsiAI/FarsInstruct) (۲۲.۷ میلیون رکورد دستورالعمل)
> 11. [`amiralifirouzi/tiny-persian-sft-15k`](https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k) (۱۵ هزار رکورد SFT)
> **مدل‌های هدف:** `Qwen/Qwen3-4B-Instruct` | `Qwen/Qwen2.5-0.5B-Instruct` | `Qwen/Qwen2.5-1.5B-Instruct` | `unsloth/Qwen2.5-1.5B-Instruct`
> **اسکریپت آموزش:** `scripts/train_qwen3_persian.py`

---

## ۱. چکیده و هدف

این اسکریپت و راهنما امکان آموزش و فاین‌تیون مدل‌های **Qwen 3** و **Qwen 2.5** را روی ۱۱ دیتاست جامع و کاربردی فارسی، ترجمه، استدلال، مناظره و ابزارهای عامل هوشمند (Agent Tool-Use) با استفاده از فریم‌ورک **Unsloth** و **Hugging Face TRL / SFTTrainer** فراهم می‌کند.

پس از آموزش، اسکریپت امکان خروجی گرفتن مستقیم به فرمت **GGUF** (نظیر `q4_k_m` و `q8_0`) را دارد که می‌توانید آن را مستقیماً داخل برنامهٔ اندروید **هوشیار (AAI)** بارگذاری کنید.

---

## ۲. مشخصات دیتاست جدید `XYZAILab/XYZ-Aquila-SFT`

- **حجم و کاربرد:** ۷,۰۰۰ مسیر گفتگو و اجرای چندنوبتی ابزارها (`web_search`, `scrape_and_extract_info`, `run_python_code`) به زبان‌های انگلیسی (`en` - ۵۰۰۰ نمونه) و چینی (`zh` - ۲۰۰۰ نمونه).
- **فرمت فیلدها:** شامل ستون `trajectory` که مستقیماً شامل نوبت‌های گفتگو با نقش‌های `system`, `user`, `assistant` و نتایج ابزار است.

---

## ۳. پیش‌نیازها و نصب وابستگی‌ها

برای اجرای فرآیند آموزش روی محیط دارای پردازندهٔ گرافیکی (GPU):

```bash
pip install unsloth trl transformers datasets accelerate peft bitsandbytes
```

---

## ۴. نحوهٔ اجرای اسکریپت آموزش

### ۴.۱ بررسی اولیه (Dry-Run / تست بدون نیاز به GPU)

```bash
# تست دیتاست ابزار و عامل هوشمند XYZ-Aquila-SFT (پیکربندی انگلیسی):
python3 scripts/train_qwen3_persian.py --dataset XYZAILab/XYZ-Aquila-SFT --dataset-config en --dry-run

# تست دیتاست XYZ-Aquila-SFT (پیکربندی چینی):
python3 scripts/train_qwen3_persian.py --dataset XYZAILab/XYZ-Aquila-SFT --dataset-config zh --dry-run
```

### ۴.۲ آموزش کامل روی `XYZAILab/XYZ-Aquila-SFT` (عامل هوشمند و جستجو)

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset XYZAILab/XYZ-Aquila-SFT \
    --dataset-config en \
    --output-dir ./qwen3-aquila-lora \
    --epochs 1 \
    --export-gguf q4_k_m
```

---

## ۵. جدول خلاصهٔ ۱۱ دیتاست پشتیبانی‌شده

| نام دیتاست | نوع داده | دستور اجرای Dry-Run |
|---|---|---|
| [`XYZAILab/XYZ-Aquila-SFT`](https://huggingface.co/datasets/XYZAILab/XYZ-Aquila-SFT) | عامل هوشمند و فراخوانی ابزار | `--dataset XYZAILab/XYZ-Aquila-SFT --dataset-config en` |
| [`nassimjp/Farsi-Reasoning-SFT`](https://huggingface.co/datasets/nassimjp/Farsi-Reasoning-SFT) | استدلال فارسی با `<think>` | `--dataset nassimjp/Farsi-Reasoning-SFT` |
| [`frasalvi/debategpt`](https://huggingface.co/datasets/frasalvi/debategpt) | گفتگوهای مناظره | `--dataset frasalvi/debategpt` |
| [`DebateLabKIT/deep-argmap-conversations`](https://huggingface.co/datasets/DebateLabKIT/deep-argmap-conversations) | نقشه استدلال | `--dataset DebateLabKIT/deep-argmap-conversations` |
| [`persiannlp/parsinlu_translation_en_fa`](https://huggingface.co/datasets/persiannlp/parsinlu_translation_en_fa) | ترجمه انگلیسی به فارسی | `--dataset persiannlp/parsinlu_translation_en_fa` |
| [`Helsinki-NLP/opus-100`](https://huggingface.co/datasets/Helsinki-NLP/opus-100) | ترجمه عمومی `en-fa` | `--dataset Helsinki-NLP/opus-100 --dataset-config en-fa` |
| [`amiralifirouzi/jev-data-persian`](https://huggingface.co/datasets/amiralifirouzi/jev-data-persian) | استدلال JEV فارسی | `--dataset amiralifirouzi/jev-data-persian` |
| [`amiralifirouzi/Persian-SFT-102K`](https://huggingface.co/datasets/amiralifirouzi/Persian-SFT-102K) | ۱۰۲ هزار رکورد SFT | `--dataset amiralifirouzi/Persian-SFT-102K` |
| [`xmanii/Persian-Math-SFT`](https://huggingface.co/datasets/xmanii/Persian-Math-SFT) | استدلال ریاضی فارسی | `--dataset xmanii/Persian-Math-SFT` |
| [`ParsiAI/FarsInstruct`](https://huggingface.co/datasets/ParsiAI/FarsInstruct) | دستورالعمل | `--dataset ParsiAI/FarsInstruct` |
| [`amiralifirouzi/tiny-persian-sft-15k`](https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k) | ۱۵ هزار رکورد SFT | `--dataset amiralifirouzi/tiny-persian-sft-15k` |

---

## ۶. استفاده از مدل خروجی در برنامهٔ هوشیار (AAI)

1. پس از اتمام آموزش، فایل GGUF خروجی در مسیر `./qwen3-aquila-lora/gguf/model-unsloth.Q4_K_M.gguf` ایجاد می‌شود.
2. فایل GGUF را به گوشی همراه خود منتقل کنید.
3. در برنامهٔ هوشیار، به زبانهٔ **تنظیمات** رفته و گزینهٔ **«بازیابی فایل مدل»** را انتخاب کنید.
4. مدل هوشیار به صورت ۱۰۰٪ آفلاین و روی دستگاه فعال شده و گفتگوها را پاسخ می‌دهد.

---

*پایان راهنما — برای آزمون‌های خودکار: `PYTHONPATH=. python3 -m unittest scripts/test_train_qwen3.py`*
