package ir.aai.hushyar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text as Txt
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.Listing
import ir.aai.hushyar.domain.MemoryBank
import ir.aai.hushyar.domain.MemoryItem

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val questions = vm.interviewQuestions()
    var step by remember { mutableStateOf(0) }
    val local = remember { mutableStateMapOf<String, String>() }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(vm.answers) {
        local.putAll(vm.answers)
        if (draft.isEmpty()) draft = local[questions[step].id].orEmpty()
    }

    val current = questions[step]
    val isLast = step == questions.lastIndex

    ScreenColumn {
        Text("معرفی بازار", style = MaterialTheme.typography.titleLarge)
        Body("هیچ بازاری داخل برنامه ساخته نشده است. با پاسخ به این پرسش‌ها، دامنه، ویژگی‌ها و وظایف من مشخص می‌شود؛ بعد از این هم می‌توانی بازار را با JSON عوض کنی.")
        LinearProgressIndicator(
            progress = { (step + 1f) / questions.size },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "گام ${Txt.numEn((step + 1).toLong())} از ${Txt.numEn(questions.size.toLong())}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.prompt, style = MaterialTheme.typography.titleMedium)
                Text(
                    current.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("پاسخ") },
            modifier = Modifier.fillMaxWidth().height(140.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    local[current.id] = draft
                    vm.saveAnswer(current.id, draft)
                    if (step > 0) step -= 1
                    draft = local[questions[step].id].orEmpty()
                },
                enabled = step > 0
            ) { Text("قبلی") }

            if (!isLast) {
                Button(
                    onClick = {
                        local[current.id] = draft
                        vm.saveAnswer(current.id, draft)
                        step += 1
                        draft = local[questions[step].id].orEmpty()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("بعدی") }
            } else {
                Button(
                    onClick = {
                        local[current.id] = draft
                        vm.saveAnswersAndBuild(local.toMap())
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("ساخت بازار") }
            }
        }

        vm.lastBuilt?.let { built ->
            if (!built.report.ok) {
                SectionTitle("ایرادها")
                built.report.blocking.forEach { BulletRow(it) }
            }
            if (built.report.warnings.isNotEmpty()) {
                SectionTitle("هشدارها")
                built.report.warnings.forEach { BulletRow(it) }
            }
        }

        HorizontalDivider()
        SectionTitle("بازار را از JSON هم می‌شود ساخت")
        Body("اگر پک آماده داری، در تب «خودم» می‌توانی JSON را وارد کنی.")
        Body("پرسش، حافظه، جستجوی وب و پشتیبان‌گیری بدون بازار هم کار می‌کنند؛ بازار فقط دامنهٔ تحلیل را تعیین می‌کند.")
    }
}

private enum class DataTab(val label: String) {
    Listings("آگهی‌ها"),
    Knowledge("دانش"),
    Memory("حافظه"),
    Backup("پشتیبان"),
    Log("لاگ")
}

@Composable
fun DataScreen(vm: AppViewModel) {
    var tab by remember { mutableStateOf(DataTab.Listings) }
    LaunchedEffect(Unit) { vm.refreshData() }

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label) }
                )
            }
        }
        HorizontalDivider()
        when (tab) {
            DataTab.Listings -> ListingsTab(vm)
            DataTab.Knowledge -> KnowledgeTab(vm)
            DataTab.Memory -> MemoryTab(vm)
            DataTab.Backup -> BackupTab(vm)
            DataTab.Log -> LogTab(vm)
        }
    }
}

@Composable
private fun ListingsTab(vm: AppViewModel) {
    val pack = vm.pack
    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var csv by remember { mutableStateOf("") }
    val manual = remember { mutableStateMapOf<String, String>() }
    var editing by remember { mutableStateOf<Listing?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }

    if (pack == null) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("بازاری تعریف نشده است.", style = MaterialTheme.typography.titleMedium)
                Body("برای ثبت آگهی و تحلیل، اول بازار را در تب «خانه» بساز. آگهی‌های موجود همچنان قابل ویرایش و حذف هستند.")
            }
        }
    } else {
        SectionTitle("افزودن آگهی")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("عنوان آگهی") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            label = { Text("قیمت (متن آزاد: ۱٫۲۵۰٫۰۰۰ تومان / ۱.۲ میلیون)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("منبع (مثلاً: فهرست خودم)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        pack.attributes.take(6).forEach { attr ->
            OutlinedTextField(
                value = manual[attr.id].orEmpty(),
                onValueChange = { manual[attr.id] = it },
                label = { Text(attr.label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        Button(
            onClick = {
                vm.addListing(title, price, source, manual.toMap())
                title = ""
                price = ""
                manual.clear()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ثبت آگهی") }

        HorizontalDivider()
        SectionTitle("ورود گروهی (CSV)")
        Body("ستون‌ها: title,price,source — یا سرستون‌های فارسی «عنوان، قیمت، منبع». ستون هم‌نام با ویژگی‌های بازار، به همان ویژگی نگاشت می‌شود.")
        OutlinedTextField(
            value = csv,
            onValueChange = { csv = it },
            label = { Text("محتوای CSV") },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            textStyle = MaterialTheme.typography.labelSmall
        )
        Button(
            onClick = { vm.importCsv(csv) },
            enabled = csv.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("وارد کردن CSV") }
        HorizontalDivider()
    }

    SectionTitle("آگهی‌ها (${Txt.numEn(vm.listings.size.toLong())})")
    if (vm.listings.isEmpty()) {
        Body("خالی است.")
    } else {
        vm.listings.take(60).forEach { listing ->
            ListingRow(
                listing = listing,
                onDelete = { vm.deleteListing(listing.id) },
                onEdit = {
                    editing = listing
                    editTitle = listing.title
                    editPrice = listing.priceRaw
                }
            )
        }
    }

    editing?.let { listing ->
        SectionTitle("ویرایش رکورد ${listing.id}")
        OutlinedTextField(
            value = editTitle,
            onValueChange = { editTitle = it },
            label = { Text("عنوان") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = editPrice,
            onValueChange = { editPrice = it },
            label = { Text("قیمت خام") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.editListing(listing, editTitle, editPrice)
                    editing = null
                },
                modifier = Modifier.weight(1f)
            ) { Text("ذخیره") }
            OutlinedButton(onClick = { editing = null }) { Text("انصراف") }
        }
    }
}

@Composable
private fun KnowledgeTab(vm: AppViewModel) {
    var editing by remember { mutableStateOf<KnowledgeItem?>(null) }
    var editClaim by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf(MemoryBank.CAT_FACT) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshData() }

    SectionTitle("دانش هوش (${Txt.numEn(vm.knowledge.size.toLong())})")
    Body("این‌ها را خودِ هوش از وب پیدا کرده، دسته‌بندی کرده و ذخیره کرده است. هر ردیف منبع، تاریخ بازبینی و تعداد منابع هم‌رأی دارد؛ ردیف منسوخ، تاریخ نگه داشته می‌شود نه پاک.")
    Body(vm.knowledgeSummary())

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("quick" to "سریع", "balanced" to "متعادل", "deep" to "عمیق").forEach { (key, label) ->
            FilterChip(
                selected = vm.researchDepth == key,
                onClick = { vm.chooseResearchDepth(key) },
                label = { Text(label) }
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.refreshKnowledge() }, enabled = !vm.loading, modifier = Modifier.weight(1f)) {
            Text("بروزرسانی کهنه‌ها")
        }
        OutlinedButton(onClick = { vm.exportMarkdown() }, modifier = Modifier.weight(1f)) {
            Text("خروجی .md")
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("جستجو در دانش") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    val shown = if (query.isBlank()) vm.knowledge else vm.knowledge.filter {
        it.claim.contains(query, ignoreCase = true) || it.domain.contains(query, ignoreCase = true) ||
            it.category.contains(query)
    }
    if (shown.isEmpty()) Body("هنوز چیزی از وب یاد نگرفته‌ام؛ یک پرسش بپرس یا بنویس «تحقیق کن: ...».")

    shown.take(80).forEach { item ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (item.supersededById != null) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.claim, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${item.category} • ${item.domain.ifBlank { "—" }} • بازبینی ${Jalali.format(item.lastVerifiedAt)}" +
                        " • اعتماد ${"%.2f".format(item.confidence)} • منابع ${Txt.numEn(item.sourceCount.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.numericValue != null) {
                    Text(
                        "مقدار عددی: ${Txt.num(item.numericValue.toLong())} ${item.unit.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (item.supersededById != null) {
                    Text("منسوخ (با دادهٔ تازه‌تر جایگزین شد)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        editing = item
                        editClaim = item.claim
                        editCategory = item.category
                    }) { Text("ویرایش") }
                    TextButton(onClick = { vm.deleteKnowledge(item.id) }) { Text("حذف") }
                }
            }
        }
    }

    editing?.let { item ->
        SectionTitle("ویرایش ${item.id}")
        OutlinedTextField(
            value = editClaim,
            onValueChange = { editClaim = it },
            label = { Text("متن دانش") },
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MemoryBank.categories.take(6).forEach { category ->
                FilterChip(
                    selected = editCategory == category,
                    onClick = { editCategory = category },
                    label = { Text(category) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.updateKnowledge(item, editClaim, editCategory)
                    editing = null
                },
                modifier = Modifier.weight(1f)
            ) { Text("ذخیره") }
            OutlinedButton(onClick = { editing = null }) { Text("انصراف") }
        }
    }
}

@Composable
private fun MemoryTab(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf(MemoryBank.CAT_GENERAL) }

    LaunchedEffect(Unit) { vm.refreshData() }

    SectionTitle("حافظهٔ هوش (${Txt.numEn(vm.memories.size.toLong())})")
    Body("هر رکورد را می‌توانی مستقیم ویرایش یا حذف کنی. هوش از همین رکوردها یاد می‌گیرد و هر تغییر در لاگ ثبت می‌شود.")
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("جستجو در حافظه") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.consolidateMemory() }, modifier = Modifier.weight(1f)) {
            Text("ادغام تکراری‌ها")
        }
        OutlinedButton(onClick = { vm.exportMarkdown() }, modifier = Modifier.weight(1f)) {
            Text("خروجی .md")
        }
    }

    val shown = if (query.isBlank()) vm.memories else vm.memories.filter {
        it.text.contains(query, ignoreCase = true) || it.category.contains(query)
    }
    if (shown.isEmpty()) Body("رکوردی نیست.")
    shown.take(80).forEach { item ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${item.category} • اهمیت ${"%.2f".format(item.importance)} • ${item.source} • ${Jalali.format(item.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.tags.isNotEmpty()) {
                    Text(
                        item.tags.joinToString("، "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        editing = item
                        editText = item.text
                        editCategory = item.category
                    }) { Text("ویرایش") }
                    TextButton(onClick = { vm.deleteMemory(item.id) }) { Text("حذف") }
                }
            }
        }
    }

    editing?.let { item ->
        SectionTitle("ویرایش ${item.id}")
        OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            label = { Text("متن") },
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MemoryBank.categories.take(6).forEach { category ->
                FilterChip(
                    selected = editCategory == category,
                    onClick = { editCategory = category },
                    label = { Text(category) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.updateMemory(item, editText, editCategory)
                    editing = null
                },
                modifier = Modifier.weight(1f)
            ) { Text("ذخیره") }
            OutlinedButton(onClick = { editing = null }) { Text("انصراف") }
        }
    }
}

@Composable
private fun BackupTab(vm: AppViewModel) {
    var paste by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFromUri(uri)
    }

    SectionTitle("پشتیبان کامل")
    Body("یک فایل ZIP شامل: backup.json (بازگردانی کامل)، brain.md (دفتر هوش)، لاگ و حافظه. چک‌سام SHA-256 داخل فایل نوشته می‌شود و بازیابی بدون تطابق چک‌سام انجام نمی‌شود.")
    Button(onClick = { vm.backup() }, modifier = Modifier.fillMaxWidth()) { Text("ساخت بک‌آپ") }

    HorizontalDivider()
    SectionTitle("بازیابی")
    Button(
        onClick = { picker.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) },
        modifier = Modifier.fillMaxWidth()
    ) { Text("انتخاب فایل پشتیبان از حافظه") }
    OutlinedTextField(
        value = paste,
        onValueChange = { paste = it },
        label = { Text("یا محتوای backup.json را بچسبان") },
        modifier = Modifier.fillMaxWidth().height(140.dp),
        textStyle = MaterialTheme.typography.labelSmall
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.inspectBackupText(paste) }, enabled = paste.isNotBlank()) { Text("بررسی سلامت") }
        Button(onClick = { vm.restoreFromText(paste) }, enabled = paste.isNotBlank()) { Text("بازیابی") }
    }

    HorizontalDivider()
    SectionTitle("پشتیبان‌های روی دستگاه")
    if (vm.backupFiles.isEmpty()) Body("هنوز پشتیبانی ساخته نشده.")
    vm.backupFiles.take(30).forEach { name ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.readBackupPreview(name) }) { Text("بررسی") }
                    if (name.endsWith(".zip")) {
                        TextButton(onClick = { vm.restoreBackupFile(name) }) { Text("بازیابی") }
                    }
                }
            }
        }
    }

    HorizontalDivider()
    SectionTitle("فایل‌های اپ")
    Body("مسیر: /data/data/ir.aai.hushyar/files/hushyar/ — بدون هیچ مجوز ذخیره‌سازی.")
    vm.dataFiles.take(20).forEach { Body(it) }
}

@Composable
private fun LogTab(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshData() }

    SectionTitle("گزارش فعالیت (${Txt.numEn(vm.events.size.toLong())})")
    Body("هر رویداد در حافظهٔ اپ ثبت می‌شود و به Markdown هم درمی‌آید. هوش برای پاسخ‌دادن این لاگ را می‌خواند.")
    Button(onClick = { vm.exportMarkdown() }, modifier = Modifier.fillMaxWidth()) {
        Text("نوشتن دفتر هوش به .md")
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("جستجو در لاگ") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    val shown = if (query.isBlank()) vm.events else vm.events.filter {
        it.message.contains(query, ignoreCase = true) || it.kind.contains(query, ignoreCase = true)
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
        items(shown.take(150)) { event ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "${event.kind} • ${event.actor} • ${Jalali.format(event.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(event.message, style = MaterialTheme.typography.bodyMedium)
                    if (event.detail.isNotBlank()) {
                        Text(
                            event.detail.take(200),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ListingRow(listing: Listing, onDelete: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listing.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(listing.priceIrt?.let { Txt.num(it) + " تومان" } ?: "قیمت نامعلوم")
                        append(" • ")
                        append(listing.source)
                        append(" • ")
                        append(listing.priceNote)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (listing.attrs.isNotEmpty()) {
                    Text(
                        listing.attrs.entries.joinToString("، ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("ویرایش") }
            TextButton(onClick = onDelete) { Text("حذف") }
        }
    }
}

@Composable
fun AskScreen(vm: AppViewModel) {
    var question by remember { mutableStateOf("") }
    val answer = vm.lastAnswer

    ScreenColumn {
        SectionTitle("بپرس")
        Body("پشتیبانی فارسی و انگلیسی. مسیر پاسخ: دستور داده → کاتالوگ محلی → حافظه → جستجوی وب چند-موتوره. هر عدد در پاسخ به یک منبع یا رکورد وصل است؛ اگر شاهدی نباشد، امتناع می‌کنم.")
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("پرسش") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.ask(question) },
                enabled = question.isNotBlank() && !vm.loading,
                modifier = Modifier.weight(1f)
            ) { Text(if (vm.loading) "در حال کار…" else "پرسیدن") }
            OutlinedButton(onClick = { question = "" }) { Text("پاک") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "ذخیره کن: سقف بودجه‌ام ۲۰ میلیون است",
                "تحقیق کن: قیمت گوشی سامسونگ S24",
                "بروزرسانی کن",
                "چه چیزهایی یاد گرفتی؟"
            ).forEach { sample ->
                TextButton(onClick = { question = sample }) {
                    Text(sample.take(18), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        answer?.let { a ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (a.refused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (a.refused) "پاسخ ندادم" else "پاسخ",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (a.refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                    Text(a.text, style = MaterialTheme.typography.bodyLarge)
                    a.bullets.forEach { BulletRow(it) }
                    if (a.citations.isNotEmpty()) {
                        Text("شاهدها", style = MaterialTheme.typography.titleMedium)
                        a.citations.forEach { c ->
                            Body("${c.ref} — ${c.label}" + (c.url?.let { " — $it" } ?: ""))
                        }
                    }
                    if (a.missing.isNotEmpty()) {
                        Text("کمبودها", style = MaterialTheme.typography.titleMedium)
                        a.missing.forEach { BulletRow(it) }
                    }
                    if (a.learned.isNotEmpty()) {
                        Text("آنچه آموختم", style = MaterialTheme.typography.titleMedium)
                        a.learned.forEach { BulletRow(it) }
                    }
                    if (a.actions.isNotEmpty()) {
                        Text("تغییرات داده", style = MaterialTheme.typography.titleMedium)
                        a.actions.forEach { BulletRow(it) }
                    }
                    Text(
                        "نیت: ${a.intent} • اطمینان: ${"%.2f".format(a.confidence)} • زبان: ${a.lang}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (vm.steps.isNotEmpty()) {
            SectionTitle("مسیر استدلال")
            vm.steps.take(12).forEach { step ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${step.index}. ${step.tool}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(step.thought, style = MaterialTheme.typography.bodyMedium)
                        if (step.observation.isNotBlank()) {
                            Text(
                                step.observation,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (vm.qaHistory.isNotEmpty()) {
            SectionTitle("تاریخچه")
            LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                items(vm.qaHistory) { (q, a) ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(q, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                a.text,
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
