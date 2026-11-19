package ir.aai.hushyar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text as PersianNumber
import ir.aai.hushyar.domain.Answer
import ir.aai.hushyar.domain.ChatTurn
import ir.aai.hushyar.domain.Citation
import ir.aai.hushyar.domain.Grounding

/** تب پرسش: ویندو کانتنت + پرسش + تفکر سریع/عمیق */
@Composable
fun AskScreen(vm: AppViewModel) {
    var draft by rememberSaveable(vm.selectedChatId) { mutableStateOf("") }
    var showThreads by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val turns = vm.chatTurns
    val busy = vm.chatBusy || vm.loading
    val current = vm.chatThreads.firstOrNull { it.id == vm.selectedChatId }

    LaunchedEffect(vm.selectedChatId, turns.size, turns.lastOrNull()?.answer, turns.lastOrNull()?.failure) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("پرسش", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showThreads = true }, enabled = !busy) {
                    Text("${current?.title ?: "گفت‌وگوی تازه"}  ▾", maxLines = 1)
                }
                DropdownMenu(
                    expanded = showThreads,
                    onDismissRequest = { showThreads = false },
                    modifier = Modifier.heightIn(max = 390.dp)
                ) {
                    if (vm.chatThreads.isEmpty()) {
                        DropdownMenuItem(text = { Text("هنوز گفت‌وگویی ثبت نشده") }, onClick = { showThreads = false })
                    }
                    vm.chatThreads.forEach { thread ->
                        DropdownMenuItem(
                            text = { Text((if (thread.id == vm.selectedChatId) "✓ " else "") + thread.title, maxLines = 2) },
                            onClick = { showThreads = false; vm.selectChat(thread.id) }
                        )
                    }
                }
            }
            TextButton(onClick = vm::newChat, enabled = !busy) { Text("+ تازه") }
        }
        if (current != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { renameText = current.title; showRename = true }, enabled = !busy) { Text("تغییر عنوان") }
                TextButton(onClick = { confirmDelete = current.id }, enabled = !busy) { Text("حذف گفتگو", color = MaterialTheme.colorScheme.error) }
            }
        }
        // ویندو کانتنت — زمینهٔ همین گفتگو (حافظه کوتاه‌مدت)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("پنجرهٔ زمینه — ${turns.size} پیام در حافظه", style = MaterialTheme.typography.titleSmall)
                if (turns.isEmpty()) {
                    Text("هنوز پیامی نیست — هر پرسش جدید زمینه می‌سازد. تکمیل پرسش قبلی را همین‌جا بگو، زمینه می‌ماند.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // نمایش 2-3 نوبت آخر به عنوان زمینه فعال (Qwen هم همین را می‌بیند)
                    turns.takeLast(3).forEach { turn ->
                        val q = turn.question.take(70)
                        val a = turn.answer?.text?.take(70) ?: (turn.failure ?: "در حال پاسخ…")
                        val status = if (turn.answer?.refused == true) "— نیاز به تکمیل" else if (turn.answer != null) "✓" else "…"
                        Text("• $q → ${a.take(45)} $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        if (turn.resolvedQuestion.isNotBlank() && PersianNumber.normalize(turn.resolvedQuestion) != PersianNumber.normalize(turn.question)) {
                            Text("  برداشت: ${turn.resolvedQuestion.take(80)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    if (turns.size > 3) Text("و ${PersianNumber.num((turns.size - 3).toLong())} پیام قبلی در حافظه", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // حالت تفکر: سریع / عمیق — مستقیم روی researchDepth
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("حالت تفکر:", style = MaterialTheme.typography.labelSmall)
            FilterChip(selected = vm.researchDepth == "quick", onClick = { vm.chooseResearchDepth("quick") }, label = { Text("سریع") })
            FilterChip(selected = vm.researchDepth == "balanced", onClick = { vm.chooseResearchDepth("balanced") }, label = { Text("متعادل") })
            FilterChip(selected = vm.researchDepth == "deep", onClick = { vm.chooseResearchDepth("deep") }, label = { Text("عمیق") })
        }
        Text(
            "سریع=۲ دور/۴ صفحه، متعادل=۳/۸، عمیق=۵/۱۴ — مدل محلی اگر نصب باشد بازنویسی می‌کند.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (turns.isEmpty()) item(key = "empty") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("گفت‌وگوی تازه", style = MaterialTheme.typography.titleSmall)
                        Text("سؤالت را بنویس. «حساب کن: ...» محلی است. برای دانش آهن‌آلات مستقیم بپرس: «ورق ۲ میل قیمت؟» یا «میلگرد A3 چیست؟» — دیتابیس آفلاین جواب می‌دهد.")
                    }
                }
            }
            items(turns, key = { it.id }) { turn ->
                ChatTurnCard(turn = turn, showLiveDetails = turn.id == turns.lastOrNull()?.id && vm.steps.isNotEmpty(), vm = vm, busy = busy, onRetry = { if (vm.ask(turn.question)) draft = "" })
            }
            val lastAnswer = turns.lastOrNull()?.answer
            if (lastAnswer != null && !busy) item(key = "followups") { FollowUps(lastAnswer) { vm.ask(it) } }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                placeholder = { Text("پرسش را بنویس…") }, modifier = Modifier.weight(1f),
                minLines = 1, maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (vm.ask(draft)) draft = "" })
            )
            Button(onClick = { if (vm.ask(draft)) draft = "" }, enabled = draft.isNotBlank() && !busy) { Text("ارسال") }
        }
    }

    if (showRename && current != null) AlertDialog(
        onDismissRequest = { showRename = false },
        title = { Text("عنوان گفت‌وگو") },
        text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("عنوان") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.renameChat(current.id, renameText); showRename = false }, enabled = renameText.isNotBlank() && !busy) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = { showRename = false }) { Text("انصراف") } }
    )
    confirmDelete?.let { threadId -> AlertDialog(
        onDismissRequest = { confirmDelete = null },
        title = { Text("حذف این گفت‌وگو؟") },
        text = { Text("همهٔ پیام‌های همین گفتگو از گوشی پاک می‌شوند. دانش مشترک و پشتیبان‌ها باقی می‌مانند.") },
        confirmButton = { TextButton(onClick = { vm.deleteChat(threadId); confirmDelete = null }, enabled = !busy) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("انصراف") } }
    )}
}

@Composable
private fun ChatTurnCard(turn: ChatTurn, showLiveDetails: Boolean, vm: AppViewModel, busy: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(modifier = Modifier.fillMaxWidth(0.90f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("شما · ${Jalali.format(turn.createdAt)}", style = MaterialTheme.typography.labelSmall)
                    Text(turn.question, style = MaterialTheme.typography.bodyMedium)
                    if (turn.resolvedQuestion.isNotBlank() && PersianNumber.normalize(turn.resolvedQuestion) != PersianNumber.normalize(turn.question)) {
                        Text("برداشت از زمینه: ${turn.resolvedQuestion.take(320)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (turn.answer != null) {
            AnswerCard(turn.answer, turn.finishedAt)
            if (turn.answer.trace.isNotEmpty() || showLiveDetails) {
                Collapsible("داخل هوش چه گذشت؟", "زمینه، تصمیم، گام‌ها و شاهدها") {
                    if (showLiveDetails) {
                        vm.lastDecision?.let { d ->
                            Text("هستهٔ تصمیم: ${if (d.fromModel) "مدل محلی" else "قاعدهٔ محلی"} — ${d.action.fa}", style = MaterialTheme.typography.titleSmall)
                            Text(d.reason, style = MaterialTheme.typography.bodySmall)
                        }
                        vm.steps.take(14).forEach { step ->
                            Text("${PersianNumber.num(step.index.toLong())}. ${stepLabel(step.tool)} — ${step.thought}", style = MaterialTheme.typography.labelSmall)
                            if (step.observation.isNotBlank()) Text(step.observation.take(380), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    turn.answer.trace.take(14).forEach { line -> Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy && turn.failure == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Column { Text(turn.failure ?: if (busy) "در حال پاسخ…" else "این پیام پاسخ نگرفت؛ دوباره ارسال کن.", style = MaterialTheme.typography.bodySmall); if (!busy) TextButton(onClick = onRetry) { Text("ارسال دوباره") } }
                }
            }
        }
    }
}

private fun stepLabel(tool: String): String = when (tool) {
    "input" -> "دریافت پرسش"; "sources.fetch" -> "بازکردن منبع"; "sources.read" -> "خواندن منبع"
    "knowledge.store" -> "ثبت در دانش"; "knowledge.lookup", "knowledge.answer" -> "بررسی دانش"
    "catalog.ask" -> "بررسی داده‌های محلی"; "memory.search", "memory.write" -> "بررسی حافظه"
    "research.start", "web.search", "search.extract" -> "پژوهش وب"; "web.fetch" -> "خواندن صفحهٔ وب"
    "brain/local-model" -> "مدل محلی"; "brain/rules" -> "قاعدهٔ محلی"; else -> "گام بررسی"
}

@Composable
private fun AnswerCard(answer: Answer, finishedAt: Long) {
    Card(Modifier.fillMaxWidth(0.96f), colors = CardDefaults.cardColors(containerColor = if (answer.refused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when { answer.intent == "conversation.clarify" -> "هوشیار · پرسش روشن‌کننده"; answer.intent == "conversation.recall" -> "هوشیار · یادآوری از همین گفتگو"; answer.intent == "purchase.total" && answer.refused -> "هوشیار · اجزای لازم کامل نیست"; answer.intent == "purchase.total" -> "هوشیار · محاسبه با ورودی صریح"; answer.intent == "personal.docs" -> "هوشیار · اسناد شخصی"; answer.intent == "tool.calculator" -> "هوشیار · محاسبهٔ محلی"; answer.refused -> "هوشیار · پاسخ مستند پیدا نشد"; answer.intent == "chat/local" -> "هوشیار · مدل محلی"; answer.intent == "chat/template" -> "هوشیار · قالبی محلی"; else -> "هوشیار · ${Jalali.format(finishedAt)}" }, style = MaterialTheme.typography.labelSmall, color = if (answer.refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
            Text(answer.text, style = MaterialTheme.typography.bodyLarge)
            answer.bullets.forEach { BulletRow(it) }
            if (answer.missing.isNotEmpty()) Text((if (answer.intent == "purchase.total") "برای محاسبه لازم است: " else "پیدا نشد: ") + answer.missing.joinToString("، "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (answer.citations.isNotEmpty()) { HorizontalDivider(); Text("منبع‌های همین پاسخ", style = MaterialTheme.typography.titleSmall); answer.citations.forEach { CitationRow(it) } }
            if (answer.learned.isNotEmpty()) Text("به دانش اضافه شد: " + answer.learned.joinToString("، "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CitationRow(citation: Citation) {
    Column(Modifier.fillMaxWidth()) {
        Text(citation.label.take(120), style = MaterialTheme.typography.bodySmall)
        Text((citation.url ?: "بیرون از وب") + " — " + citation.ref, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FollowUps(answer: Answer, onAsk: (String) -> Unit) {
    val suggestions = buildList {
        if (answer.citations.isNotEmpty()) add("منبعش کجاست؟")
        if (!answer.refused && Grounding.numbers(answer.text).isNotEmpty()) add("چرا این عدد؟")
        if (!answer.refused) add("بیشتر توضیح بده"); add("الان به‌روزش کن")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ادامهٔ همین گفت‌وگو", style = MaterialTheme.typography.labelSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(suggestions) { Chip(it) { onAsk(it) } } }
    }
}
