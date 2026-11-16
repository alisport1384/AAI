package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text

/**
 * Markdown is the assistant's own log format: human-readable, diff-friendly, and the format
 * the user asked for ("لاگ اپ را برای هوش به صورت .md"). Everything here is append-only text,
 * so a log file can be opened by any editor, grepped, or fed back to the assistant.
 */
object Markdown {

    const val SCHEMA: String = "hushyar.log/1"

    private fun head(title: String, generatedAt: Long, extra: List<Pair<String, String>> = emptyList()): String =
        buildString {
            append("# ").append(title).append('\n')
            append('\n')
            append("- تاریخ (جلالی): ").append(Jalali.format(generatedAt)).append('\n')
            append("- تاریخ (ISO-8601): ").append(iso(generatedAt)).append('\n')
            append("- قالب: ").append(SCHEMA).append('\n')
            for ((key, value) in extra) append("- ").append(key).append(": ").append(value).append('\n')
            append('\n')
        }

    fun log(events: List<LogEvent>): String = buildString {
        val generatedAt = events.maxOfOrNull { it.at } ?: System.currentTimeMillis()
        append(head("گزارش فعالیت هوشیار", generatedAt, listOf("رویدادها" to Text.num(events.size.toLong()))))
        val byKind = events.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        append("## خلاصه\n\n")
        if (byKind.isEmpty()) append("رویدادی ثبت نشده است.\n\n")
        else {
            append("| نوع | تعداد |\n|---|---|\n")
            for ((kind, count) in byKind) append("| ").append(kind).append(" | ").append(Text.num(count.toLong())).append(" |\n")
            append('\n')
        }
        append("## خط زمانی\n\n")
        for (event in events.sortedByDescending { it.at }) {
            append("### ").append(Jalali.format(event.at)).append(" — ").append(event.kind).append('\n')
            append('\n')
            append("- گوینده: ").append(event.actor).append('\n')
            append("- پیام: ").append(event.message.line()).append('\n')
            if (event.detail.isNotBlank()) append("- جزئیات: ").append(event.detail.line()).append('\n')
            append("- شناسه: ").append(event.id).append('\n')
            append('\n')
        }
    }

    fun memory(items: List<MemoryItem>): String = buildString {
        append(head("حافظهٔ هوشیار", System.currentTimeMillis(), listOf("تعداد" to Text.num(items.size.toLong()))))
        val groups = items.groupBy { it.category }.toList().sortedByDescending { it.second.size }
        for ((category, group) in groups) {
            append("## ").append(category).append(" (").append(Text.num(group.size.toLong())).append(")\n\n")
            for (item in group.sortedByDescending { it.importance }) {
                append("- ").append(item.text.line()).append('\n')
                append("  - اهمیت: ").append(String.format("%.2f", item.importance))
                append(" | دفعات استفاده: ").append(Text.num(item.useCount.toLong()))
                append(" | منبع: ").append(item.source)
                append(" | تاریخ: ").append(Jalali.format(item.createdAt)).append('\n')
                if (item.tags.isNotEmpty()) append("  - برچسب‌ها: ").append(item.tags.joinToString("، ")).append('\n')
                append("  - شناسه: `").append(item.id).append("`\n")
            }
            append('\n')
        }
        if (items.isEmpty()) append("حافظه خالی است.\n")
    }

    fun answer(question: String, answer: Answer, steps: List<AgentStep>, at: Long): String = buildString {
        append(head("گفتگو", at, listOf("زبان" to answer.lang, "نیت" to answer.intent)))
        append("## پرسش\n\n").append(question.line()).append("\n\n")
        append("## پاسخ\n\n")
        if (answer.refused) append("**امتناع:** ").append(answer.text.line()).append("\n\n")
        else append(answer.text.line()).append("\n\n")
        if (answer.bullets.isNotEmpty()) {
            for (bullet in answer.bullets) append("- ").append(bullet.line()).append('\n')
            append('\n')
        }
        if (answer.missing.isNotEmpty()) {
            append("**کمبودها:** ").append(answer.missing.joinToString("؛ ")).append("\n\n")
        }
        append("اطمینان: ").append(String.format("%.2f", answer.confidence)).append("\n\n")
        if (answer.citations.isNotEmpty()) {
            append("### منابع\n\n")
            for (citation in answer.citations) {
                append("- [").append(citation.ref).append("] ").append(citation.label)
                citation.url?.let { append(" — ").append(it) }
                append('\n')
            }
            append('\n')
        }
        if (steps.isNotEmpty()) {
            append("### مسیر استدلال\n\n")
            for (step in steps) {
                append(step.index).append(". **").append(step.tool).append("** — ").append(step.thought.line())
                if (step.args.isNotEmpty()) {
                    append(" (").append(step.args.entries.joinToString("، ") { "${it.key}=${it.value.line().take(80)}" }).append(')')
                }
                append('\n')
                if (step.observation.isNotBlank()) append("   - مشاهده: ").append(step.observation.line()).append('\n')
            }
            append('\n')
        }
        if (answer.actions.isNotEmpty()) {
            append("### تغییرات داده\n\n")
            for (action in answer.actions) append("- ").append(action).append('\n')
            append('\n')
        }
        if (answer.learned.isNotEmpty()) {
            append("### آنچه آموختم\n\n")
            for (line in answer.learned) append("- ").append(line.line()).append('\n')
            append('\n')
        }
    }

    /** Knowledge the assistant acquired by itself, grouped by category, with source and age. */
    fun knowledge(items: List<KnowledgeItem>, now: Long): String = buildString {
        append(head("دانش هوشیار", now, listOf("تعداد" to Text.num(items.size.toLong()))))
        if (items.isEmpty()) {
            append("هنوز چیزی از وب یاد نگرفته‌ام.\n")
            return@buildString
        }
        for ((category, group) in items.groupBy { it.category }.toList().sortedByDescending { it.second.size }) {
            append("## ").append(category).append(" (").append(Text.num(group.size.toLong())).append(")\n\n")
            for (item in group.sortedByDescending { it.lastVerifiedAt }) {
                append("- ").append(item.claim.line()).append('\n')
                append("  - منبع: ").append(item.domain.ifBlank { "—" })
                if (item.url.isNotBlank()) append(" — ").append(item.url)
                append('\n')
                append("  - بازبینی: ").append(Jalali.format(item.lastVerifiedAt))
                append(" | اعتماد: ").append(String.format("%.2f", item.confidence))
                append(" | منابع هم‌رأی: ").append(Text.num(item.sourceCount.toLong())).append('\n')
                if (item.supersededById != null) append("  - وضعیت: با دادهٔ تازه‌تر جایگزین شد (`").append(item.supersededById).append("`)\n")
                if (item.tags.isNotEmpty()) append("  - برچسب: ").append(item.tags.joinToString("، ")).append('\n')
                append("  - شناسه: `").append(item.id).append("`\n")
            }
            append('\n')
        }
    }

    fun backupInfo(info: Backup.Info): String = buildString {
        append(head("بک‌آپ", info.createdAt, listOf("نسخهٔ اپ" to info.appVersion, "چک‌سام" to info.sha256)))
        append("| بخش | تعداد |\n|---|---|\n")
        for ((key, value) in info.counts) append("| ").append(key).append(" | ").append(Text.num(value.toLong())).append(" |\n")
    }

    /** One document with everything, used by the "خروجی هوش" button. */
    fun fullBrain(
        items: List<MemoryItem>,
        events: List<LogEvent>,
        audits: List<AuditEntry>,
        packs: List<Triple<String, String, Int>>,
        listingCount: Int,
        generatedAt: Long,
        knowledge: List<KnowledgeItem> = emptyList()
    ): String = buildString {
        append(
            head(
                "دفتر هوشیار", generatedAt,
                listOf(
                    "بسته‌های بازار" to Text.num(packs.size.toLong()),
                    "آگهی‌ها" to Text.num(listingCount.toLong()),
                    "دانسته‌ها" to Text.num(knowledge.size.toLong())
                )
            )
        )
        append("## بسته‌های بازار\n\n")
        if (packs.isEmpty()) append("—\n\n") else {
            for ((id, name, version) in packs) append("- ").append(name).append(" (`").append(id).append("`) نسخهٔ ").append(version).append('\n')
            append('\n')
        }
        append(memory(items).substringAfter("\n\n"))
        append(knowledge(knowledge, generatedAt).substringAfter("\n\n"))
        append("## ممیزی تغییرات داده\n\n")
        if (audits.isEmpty()) append("—\n\n")
        for (audit in audits) {
            append("- ").append(Jalali.format(audit.at)).append(" · ").append(audit.actor).append(" · ")
                .append(audit.action).append(" · ").append(audit.target)
            if (audit.detail.isNotBlank()) append(" — ").append(audit.detail.line())
            append('\n')
        }
        append("\n## لاگ فعالیت\n\n")
        for (event in events.take(200)) {
            append("- ").append(Jalali.format(event.at)).append(" · ").append(event.kind).append(" · ").append(event.message.line()).append('\n')
        }
    }

    private fun String.line(): String = replace(Regex("\\s+"), " ").trim()

    private fun iso(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        return fmt.format(java.util.Date(epochMillis))
    }
}
