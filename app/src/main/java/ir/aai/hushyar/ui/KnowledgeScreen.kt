package ir.aai.hushyar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Text as Txt
import ir.aai.hushyar.domain.IronKnowledge

/**
 * تب دانش: فقط محل نگهداری اطلاعات. نیازی به دیدن همهٔ ردیف‌ها نیست.
 * - تحقیق موضوعی (DeepStudy با همه موتورها شامل گردو/ذره‌بین) → ذخیره Qwen-فهم
 * - دیتابیس آفلاین IronPin (۱۸ خانواده) → یک‌بار سید می‌شود
 * - بک‌آپ تمام دانش (ZIP + Markdown)
 */
@Composable
fun KnowledgeScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshData() }
    var topic by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportBackupToUri(vm.backupFiles.firstOrNull() ?: "", uri) }

    ScreenColumn {
        SectionTitle("دانش")
        Body("اینجا انبار دانش است — لازم نیست همه را ببینی. هر چه از وب یاد گرفته شود یا از دیتابیس آفلاین بیاید اینجا ذخیره است و مدل Qwen از همین‌جا می‌خواند.")

        // آمار
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(IronKnowledge.summary(), style = MaterialTheme.typography.bodyMedium)
                Text(vm.knowledgeSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("دانش ذخیره‌شده: ${Txt.numEn(vm.knowledge.size.toLong())} نکته", style = MaterialTheme.typography.labelSmall)
            }
        }

        // دیتابیس آفلاین IronPin — دانشِ خودِ اپ (باقی می‌ماند)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("دیتابیس آفلاین آهن‌آلات (IronPin)", style = MaterialTheme.typography.titleSmall)
                Text("۱۸ خانواده: ورق، میلگرد، میلگرد بستر، تیرآهن، نبشی، ناودانی، سپری، پروفیل/قوطی، لوله، بولت/انکر، تسمه، چهارپهلو، مفتول، مش جوشی، توری، رابیتس، شمش، قراضه — با مالیات، واحدها، فرمول وزن، کاربرد. این دانشِ خودِ اپ است و همیشه می‌ماند.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.seedIronKnowledge() }, modifier = Modifier.weight(1f)) { Text("بارگذاری دیتابیس آفلاین") }
                    OutlinedButton(onClick = { vm.reseedIronKnowledge() }, modifier = Modifier.weight(1f)) { Text("بازنصب دیتابیس") }
                }
                Text("یک‌بار کافی است؛ دفعات بعد فقط بازنصب برای به‌روزرسانی.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // اسکنر آنلاین Qwen — دقیقِ روش Qwen، هر ۲۴ ساعت به‌روز
        val ctx = LocalContext.current
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اسکنر آنلاین — دانشِ قیمتِ روز (Qwen)", style = MaterialTheme.typography.titleSmall)
                Text("۲ موضوعِ اسکن‌شده: قیمت میلگرد (۱۴۳ نکته) + قیمت تیرآهن (۹۹ نکته) = ۲۴۲ نکته. چانک ۹۰۰/۱۴۰، روش lexical-scoredِ Qwen — دقیقاً همانِ QwenRag اپ.", style = MaterialTheme.typography.bodySmall)
                Text(vm.qwenSyncStatus(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("بررسی متد: ${vm.qwenVerifyFromBundle(ctx)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("منبع: https://qwen.alisport0.qzz.io/report/batch/bb9e0626-ef04-4c81-a715-d494045b7a77 — اپ هر ۲۴ ساعت خودکار به‌روز می‌کند (JobScheduler، بدون مجوز اضافه).", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.syncQwenNow(ctx) }, modifier = Modifier.weight(1f), enabled = !vm.loading) { Text("همگام‌سازی اکنون") }
                    OutlinedButton(onClick = { vm.syncQwenFromAssets(ctx) }, modifier = Modifier.weight(1f), enabled = !vm.loading) { Text("از باندل آفلاین") }
                }
                Text("دانشِ خودِ اپ دست‌نخورده می‌ماند؛ این دانش دقیقاً اضافه و هر ۲۴ ساعت نوسازی می‌شود.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        // تحقیق موضوعی — هستهٔ درخواست مهم کاربر
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تحقیق موضوعی (ذخیره برای Qwen)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "یک موضوع بده — مثلاً «قیمت ورق گالوانیزه 0.5» یا «میلگرد A3 سایز 16». همهٔ موتورها (گوگل، بینگ، داک‌داک‌گو، گردو، ذره‌بین و ۲۶ موتور دیگر + منابع شخصی‌ات) پرسیده می‌شوند، صفحه‌ها خوانده، جمله‌ها به لایه‌های پایه/ویژگی/قیمت/نظر/به‌روز/منبع بخش و تا اشباع ذخیره می‌شوند. خروجی چانک ۴۰۰-۶۰۰ توکن با متادیتا است تا Qwen کامل بفهمد.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("موضوع (فارسی یا انگلیسی)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.studyTopic(topic, deep = false) }, enabled = !vm.studyRunning && topic.isNotBlank(), modifier = Modifier.weight(1f)) { Text(if (vm.studyRunning) "در حال یادگیری…" else "تحقیق کن") }
                    OutlinedButton(onClick = { vm.studyTopic(topic, deep = true) }, enabled = !vm.studyRunning && topic.isNotBlank(), modifier = Modifier.weight(1f)) { Text("تحقیق عمیق") }
                }
                if (vm.studyRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.studySummary?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    vm.studyLayers.forEach { (layer, count, sources) -> Text("• $layer: $count نکته از $sources منبع", style = MaterialTheme.typography.bodySmall) }
                    if (vm.studyGaps.isNotEmpty()) Text("لایه‌های بی‌نتیجه: ${vm.studyGaps.joinToString("، ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    vm.studyEngineLine?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        HorizontalDivider()

        // ذخیره / بک‌آپ تمام دانش
        SectionTitle("بک‌آپ دانش")
        Text("تمام دانش (آفلاین + وب) را می‌توانی بیرون از اپ ذخیره کنی — ZIP با چک‌سام یا Markdown.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.backup() }, modifier = Modifier.weight(1f)) { Text("ساخت بک‌آپ (ZIP)") }
            OutlinedButton(onClick = { vm.exportMarkdown() }, modifier = Modifier.weight(1f)) { Text("خروجی Markdown") }
        }
        if (vm.backupFiles.isNotEmpty()) {
            Text("بک‌آپ‌های روی گوشی: ${vm.backupFiles.joinToString("، ")}", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { exporter.launch("hushyar-backup-${System.currentTimeMillis()}.zip") }, modifier = Modifier.fillMaxWidth()) { Text("ذخیرهٔ بک‌آپ بیرون از اپ") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshKnowledge() }, modifier = Modifier.weight(1f)) { Text("بروزرسانی کهنه‌ها") }
            OutlinedButton(onClick = { vm.refreshData() }, modifier = Modifier.weight(1f)) { Text("تازه‌سازی") }
        }

        // نمایش مختصر دانش (اختیاری)
        Collapsible(title = "نمایش خلاصه دانش (اختیاری)", subtitle = "${vm.knowledge.size} نکته — برای اشکال‌زدایی") {
            if (vm.knowledge.isEmpty()) Body("هنوز دانشی ذخیره نشده — اول دیتابیس را بارگذاری کن یا تحقیق موضوعی انجام بده.")
            else vm.knowledge.take(20).forEach { item ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(8.dp)) {
                        Text(item.claim.take(160), style = MaterialTheme.typography.bodySmall)
                        Text("${item.category} · ${item.layer} · ${item.domain}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
