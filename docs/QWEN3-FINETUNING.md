# راهنمای کامل آموزش و فاین‌تیون کوئن ۳ (Qwen 3) روی دیتاست Persian SFT

> **نسخه:** ۱.۰ — ۳۰ آبان ۱۴۰۵ (2026-11-20) — **پروژه:** AAI v0.16.1
> **دیتاست هدف:** [`amiralifirouzi/tiny-persian-sft-15k`](https://huggingface.co/datasets/amiralifirouzi/tiny-persian-sft-15k)
> **مدل‌های هدف:** `Qwen/Qwen3-4B-Instruct` | `Qwen/Qwen2.5-0.5B-Instruct` | `Qwen/Qwen2.5-1.5B-Instruct` | `unsloth/Qwen2.5-1.5B-Instruct`
> **اسکریپت آموزش:** `scripts/train_qwen3_persian.py`

---

## ۱. چکیده و هدف

این اسکریپت و راهنما امکان آموزش و فاین‌تیون مدل‌های **Qwen 3** و **Qwen 2.5** را روی دیتاست ۱۵ هزارتایی **`tiny-persian-sft-15k`** با استفاده از فریم‌ورک **Unsloth** و **Hugging Face TRL / SFTTrainer** فراهم می‌کند.

پس از آموزش، اسکریپت امکان خروجی گرفتن مستقیم به فرمت **GGUF** (نظیر `q4_k_m` و `q8_0`) را دارد که می‌توانید آن را مستقیماً داخل برنامهٔ اندروید **هوشیار (AAI)** بارگذاری کنید.

---

## ۲. مشخصات دیتاست `tiny-persian-sft-15k`

- **حجم و رکوردها:** شامل ۱۴,۹۳۴ نمونهٔ باکیفیت و پالایش‌شده در دو زیرمجموعه:
  - **`fa` (۱۲,۰۰۰ رکورد):** داده‌های گفتگو و دستورات فارسی
  - **`fa_replay` (۳,۰۰۰ رکورد):** داده‌های انگلیسی برای حفظ توانایی عمومی مدل
- **فرمت گفتگو:** فرمت استاندارد ChatML با ستون `messages` شامل نقش‌های `user` و `assistant` و `system`.
- **تمپلیت چت:** مطابق توصیهٔ سازندهٔ دیتاست، از چت‌تمپلیت رسمی Qwen استفاده می‌شود (`<|im_start|>role\ncontent<|im_end|>`).

---

## ۳. پیش‌نیازها و نصب وابستگی‌ها

برای اجرای فرآیند آموزش روی محیط دارای پردازندهٔ گرافیکی (GPU):

```bash
pip install unsloth trl transformers datasets accelerate peft bitsandbytes
```

---

## ۴. نحوهٔ اجرای اسکریپت آموزش

### ۴.۱ بررسی اولیه (Dry-Run / تست بدون نیاز به GPU)

برای اطمینان از صحت ساختار دیتاست و چت‌تمپلیت:

```bash
python3 scripts/train_qwen3_persian.py --dry-run
```

### ۴.۲ آموزش کامل روی GPU

```bash
python3 scripts/train_qwen3_persian.py \
    --model Qwen/Qwen2.5-0.5B-Instruct \
    --dataset amiralifirouzi/tiny-persian-sft-15k \
    --dataset-config fa \
    --output-dir ./qwen3-persian-lora \
    --epochs 1 \
    --per-device-batch-size 2 \
    --gradient-accumulation-steps 4 \
    --learning-rate 2e-4 \
    --export-gguf q4_k_m
```

---

## ۵. پارامترهای ورودی اسکریپت (`scripts/train_qwen3_persian.py`)

| پارامتر | مقدار پیش‌فرض | توضیح |
|---|---|---|
| `--model` | `Qwen/Qwen2.5-0.5B-Instruct` | نام مدل پایه در هاگینگ‌فیس |
| `--dataset` | `amiralifirouzi/tiny-persian-sft-15k` | آدرس دیتاست در HF یا مسیر فایل JSON محلی |
| `--dataset-config` | `fa` | زیرمجموعهٔ داده (`fa` یا `fa_replay` یا `all`) |
| `--max-seq-length` | `2048` | حداکثر طول توکن‌های ورودی |
| `--output-dir` | `./qwen3-persian-lora` | پوشهٔ ذخیرهٔ وزن‌های آموزش‌دیده |
| `--epochs` | `1.0` | تعداد دورهای آموزش |
| `--export-gguf` | `None` | فرمت خروجی GGUF برای اندروید (`q4_k_m`, `q8_0`, `f16`) |
| `--dry-run` | `False` | بررسی و تست منطق بدون بارگذاری وزن‌های GPU |

---

## ۶. استفاده از مدل خروجی در برنامهٔ هوشیار (AAI)

1. پس از اتمام آموزش، فایل GGUF خروجی در مسیر `./qwen3-persian-lora/gguf/model-unsloth.Q4_K_M.gguf` ایجاد می‌شود.
2. فایل GGUF را به گوشی همراه خود منتقل کنید.
3. در برنامهٔ هوشیار، به زبانهٔ **تنظیمات** رفته و گزینهٔ **«بازیابی فایل مدل»** را انتخاب کنید.
4. مدل هوشیار به صورت ۱۰۰٪ آفلاین و روی دستگاه فعال شده و گفتگوها را با توانایی بالای فارسی پاسخ می‌دهد.

---

*پایان راهنما — برای آزمون‌های خودکار: `python3 -m unittest scripts.test_train_qwen3`*
