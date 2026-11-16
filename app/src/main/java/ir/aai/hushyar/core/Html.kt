package ir.aai.hushyar.core

/**
 * Dependency-free HTML helpers: entity decoding, tag stripping, main-text extraction and
 * link harvesting. Used both for SERP parsing and for reading fetched pages on device.
 * Regex-based on purpose: no jsoup/OkHttp-parser dependency, identical behaviour in JVM tests.
 */
object Html {

    private val SCRIPT_STYLE = Regex("(?is)<(script|style|noscript|svg|iframe|template)[^>]*>.*?</\\1>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK_TAG = Regex("(?i)</?(br|p|div|section|article|li|tr|h[1-6]|blockquote|table|ul|ol|header|footer|nav|aside)[^>]*>")
    private val ANY_TAG = Regex("(?s)<[^>]+>")
    private val WHITESPACE = Regex("[ \\t\\u00A0]+")
    private val MANY_NEWLINES = Regex("\\n{3,}")

    fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = input.indexOf(';', i + 1)
            if (end < 0 || end - i > 10) { sb.append(c); i++; continue }
            val entity = input.substring(i + 1, end)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity == "nbsp" -> " "
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded != null) { sb.append(decoded); i = end + 1 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Visible text with block boundaries preserved as newlines. */
    fun toText(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = SCRIPT_STYLE.replace(s, " ")
        s = BLOCK_TAG.replace(s, "\n")
        s = ANY_TAG.replace(s, " ")
        s = decodeEntities(s)
        s = s.replace('\u00A0', ' ')
        s = WHITESPACE.replace(s, " ")
        val lines = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return MANY_NEWLINES.replace(lines.joinToString("\n"), "\n")
    }

    fun title(html: String): String? {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return null
        val raw = decodeEntities(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()
        return raw.takeIf { it.isNotEmpty() }
    }

    /**
     * Article-ish extraction: prefers the largest cluster of long paragraphs and
     * always drops navigation-ish lines. Bounded by [maxChars].
     */
    fun mainText(html: String, maxChars: Int = 6_000): String {
        val text = toText(html)
        val lines = text.split('\n').filter { it.length >= 40 }
        val body = if (lines.isEmpty()) text else lines.joinToString("\n")
        return if (body.length <= maxChars) body else body.take(maxChars)
    }

    data class Link(val url: String, val text: String)

    /** All http(s) links with their anchor text, in document order, de-duplicated by URL. */
    fun links(html: String, baseUrl: String? = null, limit: Int = 200): List<Link> {
        val out = LinkedHashMap<String, Link>()
        val re = Regex("(?is)<a\\s+([^>]*)>(.*?)</a>")
        for (match in re.findAll(html)) {
            val attrs = match.groupValues[1]
            val href = Regex("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").find(attrs)?.groupValues?.get(1) ?: continue
            val absolute = absolutize(href, baseUrl)
            if (absolute == null) continue
            val label = decodeEntities(ANY_TAG.replace(match.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            if (label.length < 3) continue
            if (out.containsKey(absolute)) continue
            out[absolute] = Link(absolute, label)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    fun absolutize(href: String, baseUrl: String?): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val lower = h.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("#")) return null
        if (lower.startsWith("http://") || lower.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:" + h
        if (!h.startsWith("/")) return null
        if (baseUrl == null) return null
        val base = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return null
        val scheme = base.scheme ?: "https"
        val host = base.host ?: return null
        return "$scheme://$host$h"
    }

    fun domainOf(url: String): String? = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
}
