# راهنمای کامل آموزش و فاین‌تیون کوئن ۳ (Qwen 3) روی دیتاست‌های FarsInstruct و Persian SFT

> **نسخه:** ۱.۱ — ۳۰ آبان ۱۴۰۵ (2026-11-20) — **پروژه:** AAI v0.16.1
> **دیتاست‌های هدف:**
>  1. [`ParsiAI/FarsInstruct`](https://huggingface.co/datasets/ParsiAI/FarsInstruct) (۲۲.۷ میلیون رکورد در ۲۳ زیرمجموعه)
>  2. [`amiralifirouzi/tiny-persian-sft-15k`](https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k) (۱۵ هزار رکورد SFT)
> **مدل‌های هدف:** `Qwen/Qwen3-4B-Instruct` | `Qwen/Qwen2.5-0.5B-Instruct` | `Qwen/Qwen2.5-1.5B-Instruct` | `unsloth/Qwen2.5-1.5B-Instruct`
> **اسکریپت آموزش:** `scripts/train_qwen3_persian.py`

---

## ۱. چکیده و هدف

این اسکریپت و راهنما امکان آموزش و فاین‌تیون مدل‌های **Qwen 3** و **Qwen 2.5** را روی دیتاست‌های دستورالعمل فارسی از جمله **`ParsiAI/FarsInstruct`** و **`amiralifirouzi/tiny-persian-sft-15k`** با استفاده از فریم‌ورک **Unsloth** و **Hugging Face TRL / SFTTrainer** فراهم می‌کند.

پس از آموزش، اسکریپت امکان خروجی گرفتن مستقیم به فرمت **GGUF** (نظیر `q4_k_m` و `q8_0`) را دارد که می‌توانید آن را مستقیماً داخل برنامهٔ اندروید **هوشیار (AAI)** بارگذاری کنید.

---

## ۲. مشخصات دیتاست `ParsiAI/FarsInstruct`

- **حجم و رکوردها:** شامل ۲۲,۷۲۱,۱۲۰ رکورد در ۲۳ زیرمجموعه تخصصی (تخیص، پرسش و پاسخ، ترجمه، تحلیل احساسات، خلاصه‌سازی و ...).
- **ستون‌های اصلی:** `inputs` (پرامپت/دستور کاربر) و `outputs` (پاسخ انتظاری).
- **تبدیل خودکار اسکریپت:** اسکریپت `train_qwen3_persian.py` ستون‌های `inputs` و `outputs` را خودکار به قالب استاندارد ChatML (`messages`) تبدیل می‌کند.
- **محدودسازی حجم داده (`--max-samples`):** به دلیل حجم بالای FarsInstruct، می‌توانید نمونه‌ها را به تعداد دلخواه (مثلاً ۵۰,۰۰۰ نمونه) جهت آموزش سریع محدود کنید.

---

## ۳. پیش‌نیازها و نصب وابستگی‌ها

برای اجرای فرآیند آموزش روی محیط دارای پردازندهٔ گرافیکی (GPU):

```bash
pip install unsloth trl transformers datasets accelerate peft bitsandbytes
```

---

## ۴. نحوهٔ اجرای اسکریپت آموزش

### ۴.۱ بررسی اولیه (Dry-Run / تست بدون نیاز به GPU)

برای دیتاست FarsInstruct:
```bash
python3 scripts/train_qwen3_persian.py --dataset ParsiAI/FarsInstruct --dry-run
```

برای دیتاست tiny-persian-sft-15k:
```bash
python3 scripts/train_qwen3_persian.py --dataset amiralifirouzi/tiny-persian-sft-15k --dry-run
```

### ۴.۲ آموزش کامل روی FarsInstruct (با نمونه‌گیری ۵۰ هزارتایی)

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset ParsiAI/FarsInstruct \
    --dataset-config default \
    --max-samples 50000 \
    --output-dir ./qwen3-farsinstruct-lora \
    --epochs 1 \
    --per-device-batch-size 2 \
    --gradient-accumulation-steps 4 \
    --learning-rate 2e-4 \
    --export-gguf q4_k_m
```

### ۴.۳ آموزش کامل روی tiny-persian-sft-15k

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset amiralifirouzi/tiny-persian-sft-15k \
    --dataset-config fa \
    --output-dir ./qwen3-persian-lora \
    --epochs 1 \
    --export-gguf q4_k_m
```

---

## ۵. پارامترهای ورودی اسکریپت (`scripts/train_qwen3_persian.py`)

| پارامتر | مقدار پیش‌فرض | توضیح |
|---|---|---|
| `--model` | `Qwen/Qwen2.5-0.5B-Instruct` | نام مدل پایه در هاگینگ‌فیس |
| `--dataset` | `amiralifirouzi/tiny-persian-sft-15k` | آدرس دیتاست در HF (مثلاً `ParsiAI/FarsInstruct`) یا مسیر فایل JSON/JSONL محلی |
| `--dataset-config` | `default` | زیرمجموعهٔ داده (`default`, `pn_sum`, `persian_qa`, `fa`, `fa_replay` و غیره) |
| `--max-samples` | `None` | حداکثر تعداد نمونه‌های انتخابی برای آموزش (مناسب برای FarsInstruct) |
| `--max-seq-length` | `2048` | حداکثر طول توکن‌های ورودی |
| `--output-dir` | `./qwen3-persian-lora` | پوشهٔ ذخیرهٔ وزن‌های آموزش‌دیده |
| `--epochs` | `1.0` | تعداد دورهای آموزش |
| `--export-gguf` | `None` | فرمت خروجی GGUF برای اندروید (`q4_k_m`, `q8_0`, `f16`) |
| `--dry-run` | `False` | بررسی و تست منطق بدون بارگذاری وزن‌های GPU |

---

## ۶. استفاده از مدل خروجی در برنامهٔ هوشیار (AAI)

1. پس از اتمام آموزش، فایل GGUF خروجی در مسیر `./qwen3-farsinstruct-lora/gguf/model-unsloth.Q4_K_M.gguf` ایجاد می‌شود.
2. فایل GGUF را به گوشی همراه خود منتقل کنید.
3. در برنامهٔ هوشیار، به زبانهٔ **تنظیمات** رفته و گزینهٔ **«بازیابی فایل مدل»** را انتخاب کنید.
4. مدل هوشیار به صورت ۱۰۰٪ آفلاین و روی دستگاه فعال شده و گفتگوها را با توانایی بالای فارسی پاسخ می‌دهد.

---

*پایان راهنما — برای آزمون‌های خودکار: `PYTHONPATH=. python3 -m unittest scripts/test_train_qwen3.py`*
