package ir.aai.hushyar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Text as PersianNumber
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.Citation

/**
 * v0.12 — تب «پرسش» از صفر.
 *
 * قاعدهٔ چیدمان: اول پاسخ، بعد شاهدها؛ همهٔ داده‌های داخلی (گام‌های استنتاج، تصمیم مدل، پوشش،
 * گزارش موتورها) در **یک** باکس بسته زیر پاسخ. کاربر پرسش می‌پرسد، جواب را می‌خواند، و فقط اگر
 * بخواهد داخل موتور را می‌بیند.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AskScreen(vm: AppViewModel) {
    var question by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val answer = vm.lastAnswer

    ScreenColumn {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("چه می‌خواهی بدانی؟", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("مثلاً: قیمت هر عدد تی‌شرت نخی چند است؟") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.ask(question) },
                        enabled = question.isNotBlank() && !vm.loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (vm.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(if (vm.loading) "در حال بررسی…" else "بپرس")
                    }
                    OutlinedButton(
                        onClick = { question = "" },
                        enabled = question.isNotBlank()
                    ) { Text("پاک") }
                }
                Text(
                    "هر عددی که می‌بینی از یک جدول، یک متنِ منبع‌دار یا یک محاسبهٔ نمایش‌داده‌شده " +
                        "می‌آید؛ اگر پیدا نشود، «پیدا نشد» می‌نویسم.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // پیشنهادهای کوتاه، به‌جای پاراگراف راهنما
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                listOf(
                    "قیمت تی‌شرت نخی چنده؟",
                    "قیمت شلوار جین عمده",
                    "دوجین چند می‌شود؟",
                    "چه چیزهایی یاد گرفتی؟",
                    "بروزرسانی کن"
                )
            ) { suggestion ->
                Chip(suggestion) {
                    question = suggestion
                    vm.ask(suggestion)
                }
            }
        }

        answer?.let { a -> AnswerCard(a, vm) }

        if (answer != null) {
            FollowUps(answer, onAsk = { text -> question = text; vm.ask(text) })
        }

        // یک باکس، همهٔ داخل برنامه
        Collapsible(
            title = "داخل هوش چه گذشت؟",
            subtitle = "تصمیم، گام‌ها، شاهدها و وضعیت موتورها"
        ) {
            vm.lastDecision?.let { d ->
                Text(
                    "هستهٔ تصمیم: " + (if (d.fromModel) "مدل محلی" else "قاعدهٔ قطعی") + " — " + d.action.fa,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(d.reason, style = MaterialTheme.typography.bodySmall)
                if (d.query.isNotBlank()) {
                    Text(
                        "عبارت جستجوی پیشنهادی: ${d.query}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (vm.steps.isNotEmpty()) {
                HorizontalDivider()
                vm.steps.take(14).forEach { step ->
                    Text(
                        "${PersianNumber.num(step.index.toLong())}. ${stepLabel(step.tool)} — ${step.thought}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (step.observation.isNotBlank()) {
                        Text(
                            step.observation,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            answer?.trace?.takeIf { it.isNotEmpty() }?.let { trace ->
                HorizontalDivider()
                trace.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (vm.growth.facts > 0) {
                HorizontalDivider()
                Text(vm.growth.summary(), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (vm.qaHistory.isNotEmpty()) {
            TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showHistory) "بستن تاریخچه (${vm.qaHistory.size})" else "تاریخچه (${vm.qaHistory.size})")
            }
            if (showHistory) {
                vm.qaHistory.take(12).forEach { (q, a) ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(q, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                a.text.take(160),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** شناسهٔ ابزار در کد و لاگ می‌ماند؛ در صفحه فقط نام فارسی نشان داده می‌شود. */
private fun stepLabel(tool: String): String = when (tool) {
    "input" -> "دریافت پرسش"
    "sources.fetch" -> "بازکردن منبع"
    "sources.read" -> "خواندن منبع"
    "knowledge.store" -> "ثبت در دانش"
    "knowledge.lookup", "knowledge.answer" -> "بررسی دانش"
    "catalog.ask" -> "بررسی داده‌های محلی"
    "memory.search", "memory.write" -> "بررسی حافظه"
    "research.start", "web.search", "search.extract" -> "پژوهش وب"
    "web.fetch" -> "خواندن صفحهٔ وب"
    "brain/local-model" -> "مدل محلی"
    "brain/rules" -> "قاعدهٔ قطعی"
    else -> "گام بررسی"
}

/** The answer itself: headline, bullets, sources. Nothing internal. */
@Composable
private fun AnswerCard(answer: Answer, vm: AppViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (answer.refused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (answer.refused) "پاسخ ندادم" else "پاسخ",
                style = MaterialTheme.typography.labelSmall,
                color = if (answer.refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            )
            Text(answer.text, style = MaterialTheme.typography.bodyLarge)
            answer.bullets.forEach { BulletRow(it) }
            if (answer.missing.isNotEmpty()) {
                Text(
                    "چیزهایی که پیدا نشد: " + answer.missing.joinToString("، "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            answer.citations.takeIf { it.isNotEmpty() }?.let { citations ->
                HorizontalDivider()
                Text("منبع", style = MaterialTheme.typography.titleSmall)
                citations.forEach { c -> CitationRow(c) }
            }
            if (answer.learned.isNotEmpty()) {
                Text(
                    "به دانش اضافه شد: " + answer.learned.joinToString("، "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One source: label, the domain it came from, and the URL — in one compact line each. */
@Composable
private fun CitationRow(citation: Citation) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            citation.label.take(120),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            (citation.url ?: "بیرون از وب") + " — " + citation.ref,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Follow-up chips built from what the answer actually contains. */
@Composable
private fun FollowUps(answer: Answer, onAsk: (String) -> Unit) {
    val subject = answer.text.substringBefore('،').substringBefore('.').take(40).trim()
    val chips = buildList {
        if (answer.citations.isNotEmpty()) add("منبعش کجاست؟")
        if (!answer.refused) add("چرا این عدد؟")
        if (subject.isNotBlank()) add("$subject را بیشتر توضیح بده")
        add("الان بروزش کن")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("پرسش بعدی", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.take(2).forEach { chip -> Chip(chip) { onAsk(chip) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.drop(2).forEach { chip -> Chip(chip) { onAsk(chip) } }
        }
    }
}
