package ir.aai.hushyar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Jalali

/**
 * v0.12 — «بک‌اند» اپ: همان صفحه‌ای که کاربر در آن می‌گوید هوش برای چه کسی و در چه محیطی کار
 * می‌کند، چه دستور کلی‌ای دارد، از کجا جستجو کند، و لاگ را می‌خواهد یا نه.
 *
 * پیش‌فرض ما بازار است (بستهٔ دانش و پرسش نمونه)، ولی هیچ‌چیز در این صفحه به یک کالا یا یک
 * فروشگاه بند نیست: هدف و محیط متن آزاد است و منابع جستجو هم فهرست قابل‌ویرایش.
 */
@Composable
fun BackendScreen(vm: AppViewModel) {
    var goal by remember { mutableStateOf(vm.goalText) }
    var environment by remember { mutableStateOf(vm.environmentText) }
    var directive by remember { mutableStateOf(vm.directiveText) }
    var sourceTitle by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.goalText, vm.environmentText, vm.directiveText) {
        goal = vm.goalText
        environment = vm.environmentText
        directive = vm.directiveText
    }

    ScreenColumn {
        SectionTitle("هدف و محیط")
        Body(
            "این سه کادر همان چیزی است که مدل و لایهٔ پژوهش پیش از هر کاری می‌خوانند. " +
                "تهی گذاشتن‌شان مجاز است؛ آن‌وقت اپ با پیش‌فرض «بازار» کار می‌کند."
        )
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("هدف من") },
            placeholder = { Text("مثلاً: فروش عمدهٔ پوشاک؛ می‌خواهم قیمت روز و حاشیهٔ سود را بدانم") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = environment,
            onValueChange = { environment = it },
            label = { Text("محیط کار") },
            placeholder = { Text("مثلاً: بازار تهران، خرید دوجینی، مشتری‌های شهرستانی") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = directive,
            onValueChange = { directive = it },
            label = { Text("پرامپت کلی (دستور به مدل)") },
            placeholder = {
                Text("مثلاً: کوتاه و بی‌تعارف جواب بده؛ همیشه واحد و تاریخ را بنویس")
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Button(
            onClick = { vm.saveBackend(goal, environment, directive) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ذخیره") }
        if (vm.directive().isNotBlank()) {
            Text(
                "آنچه مدل می‌بیند: " + vm.directive(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
        SectionTitle("منابع جستجو")
        Body(
            "پیش‌فرض‌ها: Bing (با mkt)، Google، ویکی‌پدیا، Dogpile، DuckDuckGo و چند موتور دیگر. " +
                "هر سایتی که برای کارت معتبر است را با قالب جستجویش اضافه کن؛ جای {q} عبارت جستجو " +
                "گذاشته می‌شود. منابع تو با اولویت پرسیده می‌شوند."
        )
        vm.sources.forEach { source ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            source.template + " — " + source.format.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = source.enabled, onCheckedChange = { vm.toggleSource(source.id) })
                    TextButton(onClick = { vm.removeSource(source.id) }) { Text("حذف") }
                }
            }
        }
        OutlinedTextField(
            value = sourceTitle,
            onValueChange = { sourceTitle = it },
            label = { Text("نام منبع") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = sourceUrl,
            onValueChange = { sourceUrl = it },
            label = { Text("قالب جستجو") },
            placeholder = { Text("https://example.com/search?q={q}") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val error = vm.addSource(sourceTitle, sourceUrl, "html")
                    sourceError = error
                    if (error == null) {
                        sourceTitle = ""
                        sourceUrl = ""
                    }
                },
                enabled = sourceTitle.isNotBlank() && sourceUrl.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("افزودن منبع") }
            OutlinedButton(onClick = { vm.sweepEngines() }, modifier = Modifier.weight(1f)) {
                Text(if (vm.sweeping) "در حال پویش…" else "پویش موتورها")
            }
        }
        sourceError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (vm.engineProbes.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "نتیجهٔ پویش رویهمین شبکه (${vm.engineProbes.count { it.ok }} از ${vm.engineProbes.size} پاسخ داد)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    vm.engineProbes.forEach { probe ->
                        Text(
                            (if (probe.ok) "✔ " else "✘ ") + probe.name +
                                " — " + probe.hits + " نتیجه، " + probe.millis + "ms" +
                                (if (probe.ok) "" else " — " + probe.detail.take(60)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (probe.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        SectionTitle("لاگ")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ثبت رویدادها", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "پیش‌فرض خاموش است: تا روشن نکنی هیچ رویدادی ذخیره نمی‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = vm.logging, onCheckedChange = { vm.toggleLogging(it) })
        }
        if (vm.logging) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshLog() }, modifier = Modifier.weight(1f)) { Text("تازه‌سازی") }
                OutlinedButton(onClick = { vm.clearLog() }, modifier = Modifier.weight(1f)) { Text("پاک‌کردن") }
            }
            vm.logLines.take(40).forEach { entry ->
                Text(
                    Jalali.format(entry.at) + " — " + entry.actor + " — " + entry.action + " — " +
                        entry.target + (if (entry.detail.isNotBlank()) " — " + entry.detail.take(90) else ""),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        HorizontalDivider()
        SectionTitle("هوش روی دستگاه")
        Body("همهٔ جزئیات مدل، موتور و شکست‌هایش اینجاست — بسته، تا وقتی خودت بازش کنی.")
        Collapsible(
            title = "باکس هوش مصنوعی",
            subtitle = vm.capacityLine()
        ) {
            ModelTab(vm)
        }
    }
}
