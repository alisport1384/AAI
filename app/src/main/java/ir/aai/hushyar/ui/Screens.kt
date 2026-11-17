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
import ir.aai.hushyar.domain.ModelCatalog
import ir.aai.hushyar.domain.ModelPhase
import ir.aai.hushyar.domain.ModelUiRules
import androidx.compose.material3.Switch
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
    Model("مدل محلی"),
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
            DataTab.Model -> ModelTab(vm)
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

    // v0.8: "learn everything about X" — every engine, layered, until a round adds nothing.
    var topic by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("یادگیری عمیق یک موضوع", style = MaterialTheme.typography.titleMedium)
            Text(
                "همهٔ موتورها پرسیده می‌شوند (نه چند تای ارزان)، صفحه‌ها خوانده و جمله‌ها به لایه‌های " +
                    "پایه/ویژگی‌ها/قیمت/نظرها/به‌روزرسانی/منابع بخش می‌شوند؛ تا وقتی یک دور کامل " +
                    "چیز تازه‌ای اضافه نکند ادامه می‌دهد.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("موضوع (مثلاً: هدفون سونی WH-1000XM5)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.studyTopic(topic, deep = false) },
                    enabled = !vm.studyRunning && topic.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(if (vm.studyRunning) "در حال یادگیری…" else "یاد بگیر") }
                OutlinedButton(
                    onClick = { vm.studyTopic(topic, deep = true) },
                    enabled = !vm.studyRunning && topic.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("یادگیری عمیق‌تر") }
            }
            if (vm.studyRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.studySummary?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                vm.studyLayers.forEach { (layer, count, sources) ->
                    Text(
                        "• $layer: $count نکته از $sources منبع",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (vm.studyGaps.isNotEmpty()) {
                    Text(
                        "لایه‌های بی‌نتیجه: ${vm.studyGaps.joinToString("، ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                vm.studyEngineLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    var editClaim by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf(MemoryBank.CAT_FACT) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshData() }

    // ---- v0.6: the goal in the user's own words, and honest engine health ----
    SectionTitle("هدف من")
    var goalText by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Body("هدف را همان‌طور که هست بنویس؛ خودش پلان می‌سازد و بدون پرسش تازه شروع می‌کند.")
            OutlinedTextField(
                value = goalText,
                onValueChange = { goalText = it },
                label = { Text("مثلاً: خرید گوشی سامسونگ زیر ۲۰ میلیون") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    vm.setGoal(goalText)
                    goalText = ""
                },
                enabled = goalText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت هدف و شروع کاوش") }
            if (vm.goals.isEmpty()) {
                Body("هنوز هدفی ثبت نشده.")
            } else {
                vm.goals.forEach { BulletRow(it) }
            }
        }
    }

    SectionTitle("موتورهای جستجو (${Txt.numEn(vm.engineCount.toLong())} موتور)")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Body("همهٔ موتورها بدون کلید و حساب، فقط HTML عمومی. اگر یکی جواب ندهد، بعدی پرسیده می‌شود؛ آزمون زیر نشان می‌دهد کدام‌ها الان جواب می‌دهند.")
            OutlinedButton(
                onClick = { vm.testEngines() },
                enabled = !vm.probing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.probing) "در حال آزمودن…" else "همهٔ موتورها را آزمون کن") }
            if (vm.probes.isEmpty()) {
                Body("آزمونی انجام نشده.")
            } else {
                vm.probes.take(30).forEach { (id, ok, hits) ->
                    BulletRow(
                        if (ok) "$id — ${Txt.numEn(hits.toLong())} نتیجه" else "$id — پاسخ نداد"
                    )
                }
            }
        }
    }

    // ---- v0.5: the plan it runs without being asked ----
    SectionTitle("کاوش خودپیشه")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (vm.planGoal.isBlank()) "هنوز هدفی ثبت نشده" else "هدف: ${vm.planGoal}",
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { vm.planRatio.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "پیشرفت ${(vm.planRatio * 100).toInt()}٪ • ${vm.caseSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vm.planLines.isEmpty()) {
                Body("لایه‌ای برای کاوش نیست. اول بازار و چند دادهٔ واقعی لازم است.")
            } else {
                vm.planLines.forEach { BulletRow(it) }
            }
            vm.lastProactive?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            OutlinedButton(
                onClick = { vm.exploreNow() },
                enabled = !vm.proactiveRunning && vm.planLines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (vm.proactiveRunning) "در حال کاوش…" else "یک لایه کاوش کن")
            }
            Text(
                "همین کار هر ۱۵ دقیقه یک‌بار به‌طور خودکار (JobScheduler سیستم) هم اجرا می‌شود؛ هر اجرا محدود و قابل ازسرگیری است.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

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

/**
 * v0.7 — the on-device core, visible and controllable.
 *
 * The panel never hides a failure: it prints the engine string, the exact download progress,
 * the SHA-256 result, and the model's own reply when you press "آزمایش". Without a model the
 * rest of the app keeps working exactly as in v0.6.
 */
@Composable
internal fun ModelTab(vm: AppViewModel) {
    val local = vm.localModel
    var question by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.refreshLocal()
        vm.diagnoseEngine()
    }

    // One download, forever: the file can be written out and brought back later, so a new
    // version of the app (or a new phone) never costs another 0.5 GB.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.saveModelToFile(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.restoreModelFromFile(uri) }

    SectionTitle("هستهٔ هوش مصنوعی روی دستگاه")
    Body(
        "موتور استنتاج داخل خود اپ است (llama.cpp نسخهٔ ۱.۰.۰ که برای اندروید کامپایل شده) و " +
            "مدل، فایل GGUF است که یک بار دانلود می‌شود و بعد کامل آفلاین کار می‌کند. " +
            "هیچ کلید API، حساب یا ارسال داده به بیرون در کار نیست؛ تنها ترافیک، همان یک دانلود است."
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (local.phase) {
                    ModelPhase.ENGINE_ERROR ->
                        if (local.installed) "موتور: در دسترس نیست (فایل مدل ذخیره شده)"
                        else "موتور: در دسترس نیست"
                    ModelPhase.DOWNLOADING -> "در حال دانلود"
                    ModelPhase.READY -> "مدل نصب شده"
                    ModelPhase.MISSING -> "مدلی نصب نیست"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "کتابخانه: " + local.backend.take(160),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (local.engineReady) {
                Text(
                    "مدل در حافظه: ${local.spec.title}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            local.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    SectionTitle("انتخاب مدل")
    ModelCatalog.all.forEach { spec ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${spec.sizeLabel} • ${spec.license}" + if (spec.recommended) " • پیشنهادی" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (local.specId == spec.id) {
                        Text("انتخاب‌شده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    } else {
                        TextButton(onClick = { vm.chooseModel(spec.id) }) { Text("انتخاب") }
                    }
                }
                Text(spec.note, style = MaterialTheme.typography.bodySmall)
                // v0.11.2/3: measured on *this* phone, before the user spends a gigabyte of data.
                val advisory = remember(spec.id, local.specId) { vm.fitAdvisory(spec.id) }
                advisory?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (spec.id == vm.bestModelId()) {
                    Text(
                        "بزرگ‌ترین مدلی که این گوشی می‌تواند بارگذاری کند",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    HorizontalDivider()
    SectionTitle("دانلود و نصب")
    Body(vm.capacityLine())
    Text("فایل روی حافظهٔ خصوصی اپ ذخیره می‌شود؛ حجم: ${local.spec.sizeLabel}", style = MaterialTheme.typography.bodySmall)
    when (local.phase) {
        ModelPhase.ENGINE_ERROR -> {
            // v0.8.1: an engine that cannot load is a reason to warn, not to forbid. The 0.5 GB
            // file is still worth having — it can be exported and restored on a device that can
            // run it — and the rest of the app's intelligence does not need it at all.
            Body("کتابخانهٔ موتور روی این دستگاه بارگذاری نشد.")
            Body(ModelUiRules.engineWarning(vm.engineVerdict))
            OutlinedButton(
                onClick = { vm.diagnoseEngine() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("گزارش تشخیص دستگاه") }
            vm.engineReport?.let { report ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        report,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (local.installed) {
                Body("فایل مدل روی این دستگاه ذخیره شده است (${local.spec.sizeLabel}).")
            } else {
                Button(
                    onClick = { vm.downloadModel() },
                    enabled = ModelUiRules.canDownload(local.phase, local.phase == ModelPhase.DOWNLOADING),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("دانلود فایل مدل (${local.spec.sizeLabel}) — بعداً قابل استفاده") }
                Body(
                    "این دانلود فقط فایل را ذخیره می‌کند؛ تا وقتی موتور روی این دستگاه بارگذاری " +
                        "نشود، مدل اجرا نمی‌شود. هیچ چیز دیگری در اپ به آن وابسته نیست."
                )
            }
        }
        ModelPhase.DOWNLOADING -> {
            LinearProgressIndicator(progress = { local.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${Txt.numEn(local.downloadedBytes / (1024 * 1024))} از ${local.spec.sizeLabel} — ${Txt.numEn(local.percent.toLong())}٪",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { vm.cancelDownload() }, modifier = Modifier.fillMaxWidth()) { Text("لغو") }
        }
        ModelPhase.READY -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loadModelNow() }, modifier = Modifier.weight(1f)) { Text("بارگذاری در حافظه") }
                OutlinedButton(onClick = { vm.deleteModel() }, modifier = Modifier.weight(1f)) { Text("حذف فایل") }
            }
            vm.fitAdvisory(local.specId)?.let {
                Body(
                    "هشدار بالا یعنی طبق اندازه‌گیری‌ها این مدل روی این گوشی جا نمی‌شود. دکمهٔ بالا " +
                        "باز هم تلاش می‌کند: اگر شکست بخورد چیزی خراب نمی‌شود و دلیلش با عدد نوشته می‌شود."
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("بازنویسی جملهٔ پاسخ با مدل محلی", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "اعداد، شاهدها و رد‌کردن‌ها همان‌قبل می‌مانند؛ فقط جمله بازنویسی می‌شود و " +
                            "اگر مدل عددی بی‌شاهد بسازد، پاسخ به قالب قطعی برمی‌گردد.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = local.usingForAnswers, onCheckedChange = { vm.setLocalAnswers(it) })
            }
        }
        ModelPhase.MISSING -> {
            Button(onClick = { vm.downloadModel() }, modifier = Modifier.fillMaxWidth()) {
                Text("دانلود یک‌بارهٔ مدل (${local.spec.sizeLabel})")
            }
            Body("دانلود قابل ادامه است؛ اگر قطع شد، از همان‌جا ادامه می‌دهد. حجم و SHA-256 پیش از استفاده بررسی می‌شود.")
        }
    }

    OutlinedButton(onClick = { vm.diagnoseModel() }, modifier = Modifier.fillMaxWidth()) {
        Text("گزارش مدل (فایل، حافظه، داوری)")
    }
    vm.modelReport?.let { report ->
        Card(Modifier.fillMaxWidth()) {
            Text(report, Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
        }
    }

    HorizontalDivider()
    SectionTitle("نگه‌داشتن مدل برای همیشه")
    Body(
        "مدل در حافظهٔ خصوصی اپ است و با نصب نسخهٔ تازه روی همان اپ پاک نمی‌شود؛ " +
            "ولی با حذف اپ یا «پاک‌کردن داده» از بین می‌رود. یک نسخه در گوشی ذخیره کن تا " +
            "هرگز دوباره دانلود نکنی:"
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { saveLauncher.launch(local.spec.fileName) },
            enabled = ModelUiRules.canExport(local.installed),
            modifier = Modifier.weight(1f)
        ) { Text("ذخیرهٔ فایل مدل") }
        OutlinedButton(
            onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.weight(1f)
        ) { Text("بازیابی از فایل") }
    }
    Body("بازیابی، فایل را می‌خواند و با SHA-256 تشخیص می‌دهد کدام مدل است؛ اگر با هیچ‌کدام نخواند، می‌گوید چرا و فایل را نگه نمی‌دارد.")

    HorizontalDivider()
    SectionTitle("آزمایش روی همین دستگاه")
    Button(onClick = { vm.testLocalEngine() }, modifier = Modifier.fillMaxWidth()) { Text("یک جمله با مدل بساز") }
    vm.localProof?.let {
        Card(Modifier.fillMaxWidth()) {
            Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionTitle("گفت‌وگوی محلی")
    Body("این پاسخ‌ها فقط از مدل می‌آید، شاهد وب ندارد و به دانش اپ اضافه نمی‌شود.")
    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        label = { Text("پرسش از مدل روی دستگاه") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    Button(
        onClick = { vm.askLocal(question) },
        enabled = question.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("بپرس (آفلاین)") }
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
