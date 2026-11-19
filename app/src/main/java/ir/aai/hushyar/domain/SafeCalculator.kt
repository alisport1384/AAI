package ir.aai.hushyar.domain

import java.math.BigDecimal
import java.math.MathContext

/** Arithmetic only. Never run a user's Python/JS, shell, SQL, or text returned by a web page. */
object SafeCalculator {
    data class Result(val expression: String, val value: String, val approximate: Boolean)
    private val precision = MathContext.DECIMAL64
    private val maxValue = BigDecimal("1000000000000000")

    fun compute(input: String): Result {
        require(input.length in 1..120) { "عبارت باید ۱ تا ۱۲۰ نویسه باشد" }
        val plain = buildString {
            input.forEach { ch ->
                append(when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    '٫', ',' -> '.'
                    '×' -> '*'
                    '÷' -> '/'
                    '−', '–' -> '-'
                    else -> ch
                })
            }
        }.trim()
        require(plain.isNotEmpty()) { "عبارت خالی است" }
        val parser = Parser(plain)
        val number = parser.expression()
        parser.spaces()
        require(parser.atEnd()) { "نشانهٔ نامعتبر در عبارت محاسبه" }
        return Result(plain, number.stripTrailingZeros().toPlainString(), parser.approximate)
    }

    fun answer(input: String): Answer = runCatching {
        val result = compute(input)
        Answer(
            intent = "tool.calculator",
            text = "نتیجهٔ محاسبه${if (result.approximate) " (تقریبی)" else ""}: ${result.value}",
            bullets = listOf("عبارت: ${result.expression}", "این عدد فقط حاصل محاسبه است؛ قیمت یا واقعیتِ تأییدشدهٔ وب نیست."),
            confidence = if (result.approximate) 0.85 else 1.0,
            trace = listOf("ابزار حسابگر محلی: چهار عمل، توانِ محدود و جذر؛ کد دلخواه اجرا نشد.")
        )
    }.getOrElse { error ->
        Answer("tool.calculator", "محاسبه انجام نشد: ${error.message ?: "عبارت نامعتبر"}",
            refused = true, missing = listOf("عبارت حسابی معتبر"),
            trace = listOf("ورودی خارج از دستور زبان حسابگرِ محدود رد شد؛ کدی اجرا نشد."))
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0
        var operations = 0
        var approximate = false
        fun spaces() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun atEnd(): Boolean = i == s.length
        private fun take(ch: Char): Boolean {
            spaces()
            if (i < s.length && s[i] == ch) { i++; return true }
            return false
        }
        private fun check(value: BigDecimal): BigDecimal {
            require(value.abs() <= maxValue) { "عدد یا نتیجه از حد مجاز بزرگ‌تر است" }
            require(++operations <= 60) { "عبارت بیش از حد پیچیده است" }
            return value
        }

        fun expression(): BigDecimal {
            var value = term()
            while (true) {
                value = when {
                    take('+') -> check(value.add(term(), precision))
                    take('-') -> check(value.subtract(term(), precision))
                    else -> return value
                }
            }
        }
        private fun term(): BigDecimal {
            var value = unary()
            while (true) {
                value = when {
                    take('*') -> check(value.multiply(unary(), precision))
                    take('/') -> {
                        val divisor = unary()
                        require(divisor.signum() != 0) { "تقسیم بر صفر مجاز نیست" }
                        check(try { value.divide(divisor) } catch (_: ArithmeticException) {
                            approximate = true
                            value.divide(divisor, precision)
                        })
                    }
                    else -> return value
                }
            }
        }
        private fun unary(): BigDecimal = when {
            take('+') -> unary()
            take('-') -> check(unary().negate(precision))
            else -> power()
        }
        private fun power(): BigDecimal {
            val value = atom()
            if (!take('^')) return value
            val exponent = unary()
            val n = try { exponent.intValueExact() } catch (_: ArithmeticException) {
                throw IllegalArgumentException("توان باید عدد صحیح باشد")
            }
            require(n in -10..10 && !(n < 0 && value.signum() == 0)) { "توان نامعتبر یا خارج از بازهٔ ۱۰± است" }
            val positive = check(value.pow(kotlin.math.abs(n), precision))
            if (n >= 0) return positive
            return check(try { BigDecimal.ONE.divide(positive) } catch (_: ArithmeticException) {
                approximate = true
                BigDecimal.ONE.divide(positive, precision)
            })
        }
        private fun atom(): BigDecimal {
            spaces()
            require(depth++ < 12) { "پرانتزها بیش از حد تودرتو هستند" }
            try {
                if (take('(')) {
                    val value = expression()
                    require(take(')')) { "پرانتز بسته نشده است" }
                    return value
                }
                if (s.regionMatches(i, "sqrt", 0, 4, ignoreCase = true)) {
                    i += 4
                    require(take('(')) { "جذر باید به شکل sqrt(عدد) نوشته شود" }
                    val value = expression()
                    require(take(')')) { "پرانتز جذر بسته نشده است" }
                    require(value.signum() >= 0) { "جذر عدد منفی مجاز نیست" }
                    if (value.signum() == 0) return BigDecimal.ZERO
                    val two = BigDecimal(2)
                    var guess = BigDecimal.valueOf(kotlin.math.sqrt(value.toDouble()))
                    repeat(20) { guess = guess.add(value.divide(guess, precision), precision).divide(two, precision) }
                    if (guess.multiply(guess).compareTo(value) != 0) approximate = true
                    return check(guess)
                }
                spaces()
                val start = i
                while (i < s.length && s[i].isDigit() && s[i] in '0'..'9') i++
                if (i < s.length && s[i] == '.') {
                    i++
                    while (i < s.length && s[i] in '0'..'9') i++
                }
                require(i > start && s.substring(start, i).any(Char::isDigit)) { "عدد یا عبارت حسابی معتبر لازم است" }
                return check(BigDecimal(s.substring(start, i), precision))
            } finally { depth-- }
        }
    }
}
