package ir.aai.hushyar.core

import java.util.UUID

object Ids {
    fun new(prefix: String): String = "$prefix-" + UUID.randomUUID().toString().replace("-", "").take(12)
}
