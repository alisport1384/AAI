# راهنمای کامل آموزش و فاین‌تیون کوئن ۳ (Qwen 3) روی دیتاست‌های جامع فارسی

> **نسخه:** ۱.۳ — ۳۰ آبان ۱۴۰۵ (2026-11-20) — **پروژه:** AAI v0.16.1
> **دیتاست‌های پشتیبانی‌شده:**
>  1. [`amiralifirouzi/jev-data-persian`](https://huggingface.co/datasets/amiralifirouzi/jev-data-persian) (۱۱۹ هزار رکورد استدلال و ارزیابی JEV به فارسی)
>  2. [`amiralifirouzi/Persian-SFT-102K`](https://huggingface.co/datasets/amiralifirouzi/Persian-SFT-102K) (مخزن کامل ۱۰۲ هزارتایی SFT)
>  3. [`xmanii/Persian-Math-SFT`](https://huggingface.co/datasets/xmanii/Persian-Math-SFT) (دیتاست تخصصی استدلال ریاضی)
>  4. [`ParsiAI/FarsInstruct`](https://huggingface.co/datasets/ParsiAI/FarsInstruct) (۲۲.۷ میلیون رکورد دستورالعمل)
>  5. [`amiralifirouzi/tiny-persian-sft-15k`](https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k) (۱۵ هزار رکورد SFT)
> **مدل‌های هدف:** `Qwen/Qwen3-4B-Instruct` | `Qwen/Qwen2.5-0.5B-Instruct` | `Qwen/Qwen2.5-1.5B-Instruct` | `unsloth/Qwen2.5-1.5B-Instruct`
> **اسکریپت آموزش:** `scripts/train_qwen3_persian.py`

---

## ۱. چکیده و هدف

این اسکریپت و راهنما امکان آموزش و فاین‌تیون مدل‌های **Qwen 3** و **Qwen 2.5** را روی مجموعه دیتاست‌های جامع و کاربردی فارسی با استفاده از فریم‌ورک **Unsloth** و **Hugging Face TRL / SFTTrainer** فراهم می‌کند.

پس از آموزش، اسکریپت امکان خروجی گرفتن مستقیم به فرمت **GGUF** (نظیر `q4_k_m` و `q8_0`) را دارد که می‌توانید آن را مستقیماً داخل برنامهٔ اندروید **هوشیار (AAI)** بارگذاری کنید.

---

## ۲. مشخصات دیتاست‌های پشتیبانی‌شده

### ۲.۱ دیتاست `amiralifirouzi/jev-data-persian`
- **حجم و ترکیب:** ۱۱۹,۰۰۰ نمونهٔ باکیفیت استدلال، ارزیابی، زمینه‌سازی و سوالات چندگزینه‌ای/چند‌پاسخی به زبان فارسی.
- **ستون‌ها:** `state_fa` (زمینه/سناریو)، `question_fa` (سوال)، `options_fa` (گزینه‌ها)، `answer_fa` (پاسخ نهایی).
- **تبدیل هوشمند:** اسکریپت تمام این فیلدها را ترکیب کرده و به فرمت یکپارچه ChatML تبدیل می‌کند.

### ۲.۲ دیتاست `amiralifirouzi/Persian-SFT-102K`
- **حجم و ترکیب:** ۱۰۱,۹۱۴ نمونهٔ استاندارد فارسی و انگلیسی در دو پیکربندی `fa` (۸۴ هزار رکورد) و `fa_replay` (۱۰۱ هزار رکورد).

### ۲.۳ دیتاست `xmanii/Persian-Math-SFT`
- **حجم و ترکیب:** ۱,۰۰۰ نمونهٔ تخصصی استدلال و حل مسائل ریاضی به زبان فارسی.

### ۲.۴ دیتاست `ParsiAI/FarsInstruct`
- **حجم و ترکیب:** ۲۲.۷ میلیون رکورد دستورالعمل فارسی و انگلیسی در ۲۳ زیرمجموعه.

---

## ۳. پیش‌نیازها و نصب وابستگی‌ها

برای اجرای فرآیند آموزش روی محیط دارای پردازندهٔ گرافیکی (GPU):

```bash
pip install unsloth trl transformers datasets accelerate peft bitsandbytes
```

---

## ۴. نحوهٔ اجرای اسکریپت آموزش

### ۴.۱ بررسی اولیه (Dry-Run / تست بدون نیاز به GPU)

برای دیتاست jev-data-persian:
```bash
python3 scripts/train_qwen3_persian.py --dataset amiralifirouzi/jev-data-persian --dry-run
```

برای دیتاست Persian-SFT-102K:
```bash
python3 scripts/train_qwen3_persian.py --dataset amiralifirouzi/Persian-SFT-102K --dry-run
```

### ۴.۲ آموزش کامل روی `amiralifirouzi/jev-data-persian` (۵۰ هزار نمونه)

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset amiralifirouzi/jev-data-persian \
    --max-samples 50000 \
    --output-dir ./qwen3-jev-lora \
    --epochs 1 \
    --export-gguf q4_k_m
```

### ۴.۳ آموزش کامل روی `amiralifirouzi/Persian-SFT-102K`

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset amiralifirouzi/Persian-SFT-102K \
    --dataset-config fa \
    --output-dir ./qwen3-persian-102k-lora \
    --epochs 1 \
    --export-gguf q4_k_m
```

---

## ۵. پارامترهای ورودی اسکریپت (`scripts/train_qwen3_persian.py`)

| پارامتر | مقدار پیش‌فرض | توضیح |
|---|---|---|
| `--model` | `Qwen/Qwen2.5-0.5B-Instruct` | نام مدل پایه در هاگینگ‌فیس |
| `--dataset` | `amiralifirouzi/tiny-persian-sft-15k` | آدرس دیتاست در HF (مثلاً `amiralifirouzi/jev-data-persian`) یا مسیر فایل JSON/JSONL محلی |
| `--dataset-config` | `default` | زیرمجموعهٔ داده (`default`, `fa`, `fa_replay` و غیره) |
| `--max-samples` | `None` | حداکثر تعداد نمونه‌های انتخابی برای آموزش |
| `--max-seq-length` | `2048` | حداکثر طول توکن‌های ورودی |
| `--output-dir` | `./qwen3-persian-lora` | پوشهٔ ذخیرهٔ وزن‌های آموزش‌دیده |
| `--epochs` | `1.0` | تعداد دورهای آموزش |
| `--export-gguf` | `None` | فرمت خروجی GGUF برای اندروید (`q4_k_m`, `q8_0`, `f16`) |
| `--dry-run` | `False` | بررسی و تست منطق بدون بارگذاری وزن‌های GPU |

---

## ۶. استفاده از مدل خروجی در برنامهٔ هوشیار (AAI)

1. پس از اتمام آموزش، فایل GGUF خروجی در مسیر `./qwen3-jev-lora/gguf/model-unsloth.Q4_K_M.gguf` ایجاد می‌شود.
2. فایل GGUF را به گوشی همراه خود منتقل کنید.
3. در برنامهٔ هوشیار، به زبانهٔ **تنظیمات** رفته و گزینهٔ **«بازیابی فایل مدل»** را انتخاب کنید.
4. مدل هوشیار به صورت ۱۰۰٪ آفلاین و روی دستگاه فعال شده و گفتگوها را پاسخ می‌دهد.

---

*پایان راهنما — برای آزمون‌های خودکار: `PYTHONPATH=. python3 -m unittest scripts/test_train_qwen3.py`*
