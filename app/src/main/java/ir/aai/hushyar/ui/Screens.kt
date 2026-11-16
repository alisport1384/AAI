package ir.aai.hushyar.ui

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
import ir.aai.hushyar.core.Text as Txt
import ir.aai.hushyar.domain.Listing

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
    }
}

@Composable
fun DataScreen(vm: AppViewModel) {
    val pack = vm.pack ?: return
    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var csv by remember { mutableStateOf("") }
    val manual = remember { mutableStateMapOf<String, String>() }

    ScreenColumn {
        SectionTitle("افزودن آگهی")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("عنوان آگهی") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false
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
        SectionTitle("آگهی‌ها (${Txt.numEn(vm.listings.size.toLong())})")
        if (vm.listings.isEmpty()) {
            Body("خالی است.")
        } else {
            vm.listings.take(60).forEach { listing ->
                ListingRow(listing) { vm.deleteListing(listing.id) }
            }
        }
    }
}

@Composable
fun ListingRow(listing: Listing, onDelete: () -> Unit) {
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
        Body("پاسخ‌ها فقط از دادهٔ ذخیره‌شده می‌آید؛ اگر شاهدی نباشد، صریحاً می‌گویم نمی‌دانم.")
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("پرسش") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { vm.ask(question) },
            enabled = question.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("پرسیدن") }

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
                        a.citations.forEach { c -> Body("${c.ref} — ${c.label}") }
                    }
                    if (a.missing.isNotEmpty()) {
                        Text("کمبودها", style = MaterialTheme.typography.titleMedium)
                        a.missing.forEach { BulletRow(it) }
                    }
                    Text(
                        "نیت: ${a.intent} • اطمینان: ${"%.2f".format(a.confidence)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (vm.qaHistory.isNotEmpty()) {
            SectionTitle("تاریخچه")
            LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
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
