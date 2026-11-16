package ir.aai.hushyar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Text as Txt
import ir.aai.hushyar.domain.AnalysisResult
import ir.aai.hushyar.domain.ProductGroup

enum class Tab(val label: String, val icon: String) {
    Home("خانه", "◈"),
    Data("داده", "▤"),
    Ask("پرسش", "؟"),
    Self("خودم", "◎")
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val pack = vm.pack
    if (pack == null) {
        OnboardingScreen(vm)
        return
    }

    var tab by remember { mutableStateOf(Tab.Home) }
    val analysis = vm.analysis

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.icon) },
                        label = { Text(entry.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                analysis != null -> AnalysisScreen(analysis, vm::closeAnalysis)
                tab == Tab.Home -> HomeScreen(vm)
                tab == Tab.Data -> DataScreen(vm)
                tab == Tab.Ask -> AskScreen(vm)
                else -> SelfScreen(vm)
            }

            vm.notice?.let { message ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.clearNotice() }) { Text("بستن") }
                        }
                    }
                }
            }

            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp).size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun BulletRow(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val pack = vm.pack ?: return
    ScreenColumn {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "نسخهٔ پک ${pack.version} • زبان: ${pack.langs.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pack.categories.isNotEmpty()) {
                    Text(
                        "دسته‌ها: " + pack.categories.joinToString("، "),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "ویژگی‌های محوری: " + pack.attributes.joinToString("، ") { it.label },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("آگهی‌ها", Txt.numEn(vm.listings.size.toLong()), Modifier.weight(1f))
            StatCard("کالاها", Txt.numEn(vm.products.size.toLong()), Modifier.weight(1f))
            StatCard("ویژگی‌ها", Txt.numEn(pack.attributes.size.toLong()), Modifier.weight(1f))
        }

        SectionTitle("کالاها")
        if (vm.products.isEmpty()) {
            Body("هنوز آگهی‌ای ثبت نشده. از تب «داده» یک آگهی اضافه کن یا CSV وارد کن.")
        } else {
            vm.products.forEach { product -> ProductCard(product) { vm.analyze(product) } }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ProductCard(product: ProductGroup, onAnalyze: () -> Unit) {
    val prices = product.prices
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(product.canonicalName, style = MaterialTheme.typography.titleMedium)
            val range = when {
                prices.isEmpty() -> "قیمت نامعلوم"
                prices.size == 1 -> Txt.num(prices.first()) + " تومان"
                else -> Txt.num(prices.min()) + " تا " + Txt.num(prices.max()) + " تومان"
            }
            Text(range, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Text(
                "${Txt.numEn(product.listings.size.toLong())} آگهی • ${Txt.numEn(product.attrs.size.toLong())} ویژگی",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) { Text("تحلیل") }
        }
    }
}

@Composable
fun AnalysisScreen(analysis: AnalysisResult, onBack: () -> Unit) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("بازگشت") }
            Spacer(Modifier.size(4.dp))
            Text(analysis.product.canonicalName, style = MaterialTheme.typography.titleMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(analysis.verdict, style = MaterialTheme.typography.titleLarge)
                Text(
                    "امتیاز ارزش ${"%.2f".format(analysis.valueScore)} • برچسب قیمت: ${analysis.priceBand}",
                    style = MaterialTheme.typography.bodyMedium
                )
                analysis.priceStats?.let { stats ->
                    Text(
                        "میانه ${Txt.num(stats.median)} • بازه ${Txt.num(stats.min)} تا ${Txt.num(stats.max)} • ${Txt.numEn(stats.count.toLong())} آگهی",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } ?: Text("برای این کالا قیمت قابل اتکا ندارم.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("ویژگی‌ها")
        analysis.findings.forEach { finding ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(finding.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    finding.value ?: "نامعلوم",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (finding.value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (analysis.flags.isNotEmpty()) {
            SectionTitle("هشدارها")
            analysis.flags.forEach { flag ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${flag.severity} • ${flag.id}", style = MaterialTheme.typography.labelSmall)
                        Text(flag.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (analysis.missing.isNotEmpty()) {
            SectionTitle("نامعلوم‌ها")
            analysis.missing.forEach { BulletRow(it) }
        }

        if (analysis.citations.isNotEmpty()) {
            SectionTitle("شاهدها")
            analysis.citations.forEach { citation ->
                Body("${citation.ref} — ${citation.label}")
            }
        }

        if (analysis.notes.isNotEmpty()) {
            SectionTitle("یادداشت")
            analysis.notes.forEach { BulletRow(it) }
        }
    }
}

@Composable
fun SelfScreen(vm: AppViewModel) {
    val manifest = vm.manifest
    val pack = vm.pack
    var json by remember { mutableStateOf("") }

    ScreenColumn {
        SectionTitle("من چه هستم")
        manifest?.let { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.identity, style = MaterialTheme.typography.bodyLarge)
                    HorizontalDivider()
                    Text("وظایف", style = MaterialTheme.typography.titleMedium)
                    m.duties.forEach { BulletRow(it) }
                    Text("توانایی‌ها", style = MaterialTheme.typography.titleMedium)
                    m.abilities.forEach { BulletRow(it) }
                    Text("مرزها", style = MaterialTheme.typography.titleMedium)
                    m.limits.forEach { BulletRow(it) }
                    Text("چیزهایی که نمی‌دانم", style = MaterialTheme.typography.titleMedium)
                    m.unknowns.forEach { BulletRow(it) }
                }
            }
        } ?: Body("بازاری فعال نیست.")

        SectionTitle("بازارهای ذخیره‌شده")
        if (vm.packList.isEmpty()) Body("هیچ بازاری ذخیره نشده.")
        vm.packList.forEach { (id, name, version) ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "id: $id • نسخهٔ $version" + if (pack?.id == id) " • فعال" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pack?.id != id) {
                        OutlinedButton(onClick = { vm.activatePack(id) }) { Text("فعال کن") }
                    }
                }
            }
        }

        SectionTitle("تعویض بازار با JSON")
        Body("پک بازار داده است، نه کد. برای عوض کردن بازار، JSON پک را اینجا بچسبان و وارد کن.")
        OutlinedButton(onClick = { json = vm.exportPack().orEmpty() }) { Text("نمایش JSON بازار فعلی") }
        androidx.compose.material3.OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            label = { Text("JSON پک") },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            textStyle = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.importPack(json) }, enabled = json.isNotBlank()) { Text("وارد کن") }
            OutlinedButton(onClick = { vm.resetAll() }) { Text("پاک‌سازی کامل") }
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}
