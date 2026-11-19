package ir.aai.hushyar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Jalali
import ir.aai.hushyar.core.Text as Txt

/**
 * تب تنظیمات: لاگ حرفه‌ای + مدل + موتورها + دیتابیس شخصی + تنظیمات مدل/اپ.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var sourceTitle by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var personalUrl by remember { mutableStateOf(vm.personalBackendUrl) }
    var personalPin by remember { mutableStateOf(vm.personalBackendPin) }
    var personalToken by remember { mutableStateOf("") }
    var personalError by remember { mutableStateOf<String?>(null) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreModelFromFile(uri) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.saveModelToFile(uri) }

    ScreenColumn {
        SectionTitle("تنظیمات")

        // 1) لاگ حرفه‌ای
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("لاگ حرفه‌ای", style = MaterialTheme.typography.titleSmall)
                        Text("پیش‌فرض خاموش — فقط وقتی نیاز داری روشن کن.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.logging, onCheckedChange = { vm.toggleLogging(it) })
                }
                if (vm.logging) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.refreshLog() }, modifier = Modifier.weight(1f)) { Text("تازه‌سازی") }
                        OutlinedButton(onClick = { vm.clearLog() }, modifier = Modifier.weight(1f)) { Text("پاک‌کردن") }
                    }
                    vm.logLines.take(30).forEach { e -> Text("${Jalali.format(e.at)} — ${e.actor} — ${e.action} — ${e.detail.take(70)}", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(onClick = { vm.exportLogMarkdown() }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره لاگ به .md") }
                }
                Text("تعداد رکورد لاگ: ${Txt.numEn(vm.logLines.size.toLong())} — ${vm.loggingSummary()}", style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider()

        // 2) مدل (بارگذاری/دانلود/ذخیره/تست) + تنظیمات مدل
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مدل محلی (Qwen / Gemma / Llama)", style = MaterialTheme.typography.titleSmall)
                Text(vm.capacityLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // لیست مدل‌ها
                vm.modelOptions.forEach { opt ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(opt.title, style = MaterialTheme.typography.bodyMedium)
                                Text("${opt.sizeLabel} · ${opt.note.take(80)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (vm.localModel.specId == opt.id) Text("✓", color = MaterialTheme.colorScheme.primary)
                            else OutlinedButton(onClick = { vm.chooseModel(opt.id) }) { Text("انتخاب") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadModel() }, enabled = !vm.isDownloading, modifier = Modifier.weight(1f)) { Text(if (vm.isDownloading) "در حال دانلود…" else "دانلود مدل") }
                    OutlinedButton(onClick = { vm.loadModelNow() }, modifier = Modifier.weight(1f)) { Text("بارگذاری در RAM") }
                }
                if (vm.isDownloading) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { (vm.localModel.downloadedBytes.toFloat() / (vm.localModel.totalBytes.coerceAtLeast(1).toFloat())).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${Txt.num(vm.localModel.downloadedBytes / 1024 / 1024)} / ${Txt.num(vm.localModel.totalBytes / 1024 / 1024)} MB", style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("بارگذاری از فایل") }
                    OutlinedButton(onClick = { exportPicker.launch(vm.localModel.spec.fileName) }, modifier = Modifier.weight(1f)) { Text("ذخیره مدل") }
                }
                // تست مدل
                var testPrompt by remember { mutableStateOf("سلام، ورق ۲ میل چیست؟") }
                OutlinedTextField(testPrompt, { testPrompt = it }, label = { Text("متن تست مدل") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.testModel(testPrompt) }, enabled = vm.localModel.engineReady, modifier = Modifier.fillMaxWidth()) { Text("تست مدل") }
                vm.localTestResult?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall) }
                vm.localModel.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

                // تنظیمات پیشرفته مدل (تحقیق‌شده)
                HorizontalDivider()
                Text("تنظیمات مدل (Qwen3 sampling)", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("مستقیم از مدل (بدون وب):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = vm.directModel, onCheckedChange = { vm.toggleDirectModel(it) })
                }
                Text("فعال = حساب/گفتگو مستقیم به مدل می‌رود؛ قیمت همچنان با شاهد وب است.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // نمایش پارامترهای پیشنهادی Qwen3 (اطلاعاتی)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("پیشنهاد Qwen3: temp 0.7 / top_p 0.8 / top_k 20 (non-thinking)؛ thinking: temp 0.6 / top_p 0.95 / presence_penalty 1.5 — این‌ها در llama.cpp native تنظیم می‌شوند.", style = MaterialTheme.typography.labelSmall)
                        Text("کانتکست مدل: ${vm.localModel.spec.contextTokens} توکن؛ روی گوشی 2048-4096 محدود می‌شود تا RAM کم نیاید.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        HorizontalDivider()

        // 3) موتورها و وضعیت
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("موتورهای جستجو (${Txt.numEn(vm.engineCount.toLong())} موتور)", style = MaterialTheme.typography.titleSmall)
                Text("شامل گردو و ذره‌بین + همه موتورهای قبلی. بدون کلید، فقط HTML/JSON عمومی.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.testEngines() }, enabled = !vm.probing, modifier = Modifier.fillMaxWidth()) { Text(if (vm.probing) "در حال آزمون…" else "آزمون سلامت موتورها") }
                if (vm.probes.isNotEmpty()) vm.probes.take(30).forEach { (id, ok, hits) -> BulletRow(if (ok) "$id — ${Txt.numEn(hits.toLong())} نتیجه" else "$id — پاسخ نداد") }
                else Body("آزمونی انجام نشده.")
                // افزودن منبع شخصی = دیتابیس شخصی
                HorizontalDivider()
                Text("افزودن دیتابیس / منبع شخصی", style = MaterialTheme.typography.titleSmall)
                Text("هر سایت با قالب {q} — مثلاً https://torob.com/search/?query={q} یا سایت آهن‌فروشی خودت.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(sourceTitle, { sourceTitle = it }, label = { Text("نام منبع") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("قالب جستجو (https://...{q})") }, placeholder = { Text("https://example.com/search?q={q}") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sourceError = vm.addSource(sourceTitle, sourceUrl, "html").also { if (it == null) { sourceTitle = ""; sourceUrl = "" } } }, enabled = sourceTitle.isNotBlank() && sourceUrl.isNotBlank(), modifier = Modifier.weight(1f)) { Text("افزودن منبع") }
                    OutlinedButton(onClick = { vm.sweepEngines() }, modifier = Modifier.weight(1f)) { Text(if (vm.sweeping) "در حال پویش…" else "پویش") }
                }
                sourceError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }

        HorizontalDivider()

        // 4) بک‌اند شخصی (اختیاری)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("بک‌اند شخصی (اختیاری) — بدون API پولی", style = MaterialTheme.typography.titleSmall)
                Body("بدون این سرور هم اپ کامل کار می‌کند. فقط «از اسنادم: ...» به سرور خودت می‌رود.")
                OutlinedTextField(personalUrl, { personalUrl = it }, label = { Text("نشانی HTTPS سرور شخصی") }, placeholder = { Text("https://192.168.1.10:8765") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(personalPin, { personalPin = it }, label = { Text("اثر انگشت SHA-256 (64 رقم)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(personalToken, { personalToken = it }, label = { Text("کلید دسترسی (برای ثبت)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { personalError = vm.savePersonalBackend(personalUrl, personalPin, personalToken).also { if (it == null) personalToken = "" } }, enabled = personalUrl.isNotBlank() && personalPin.isNotBlank() && personalToken.isNotBlank(), modifier = Modifier.weight(1f)) { Text("ثبت اتصال") }
                    OutlinedButton(onClick = vm::testPersonalBackend, enabled = vm.personalBackendConfigured, modifier = Modifier.weight(1f)) { Text("آزمون") }
                }
                personalError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                if (vm.personalBackendConfigured) {
                    Body("کلید در Keystore گوشی است؛ در ZIP پشتیبان نیست.")
                    OutlinedButton(onClick = vm::disconnectPersonalBackend) { Text("قطع اتصال") }
                }
                OutlinedButton(onClick = { vm.refreshPersonalDocuments() }) { Text("تازه‌سازی اسناد") }
            }
        }

        HorizontalDivider()

        // 5) تنظیمات عمومی اپ
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تنظیمات عمومی", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("quick" to "سریع", "balanced" to "متعادل", "deep" to "عمیق").forEach { (k, l) ->
                        FilterChip(selected = vm.researchDepth == k, onClick = { vm.chooseResearchDepth(k) }, label = { Text(l) })
                    }
                }
                Text("عمق پژوهش پیش‌فرض برای تب پرسش و دانش.", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = vm::resetAll, modifier = Modifier.fillMaxWidth()) { Text("پاک‌سازی کامل داده‌های گوشی") }
            }
        }
    }
}
