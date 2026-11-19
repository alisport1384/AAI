package ir.aai.hushyar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.unit.dp
import ir.aai.hushyar.AppViewModel
import ir.aai.hushyar.core.Text as Txt
import ir.aai.hushyar.domain.AnalysisResult
import ir.aai.hushyar.domain.ProductGroup

enum class Tab(val label: String, val icon: String) {
    Ask("پرسش", "؟"),
    Knowledge("دانش", "▤"),
    Settings("تنظیمات", "⚙")
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.Ask) }
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
                tab == Tab.Ask -> AskScreen(vm)
                tab == Tab.Knowledge -> KnowledgeScreen(vm)
                tab == Tab.Settings -> SettingsScreen(vm)
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
                            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearNotice() }) { Text("بستن") }
                        }
                    }
                }
            }
            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// Shared helpers used across all three tabs
@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
}

@Composable
fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun BulletRow(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text("•", color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                Text("امتیاز ارزش ${\"%.2f\".format(analysis.valueScore)} • برچسب قیمت: ${analysis.priceBand}", style = MaterialTheme.typography.bodyMedium)
                analysis.priceStats?.let { stats -> Text("میانه ${Txt.num(stats.median)} • بازه ${Txt.num(stats.min)} تا ${Txt.num(stats.max)} • ${Txt.numEn(stats.count.toLong())} آگهی", style = MaterialTheme.typography.bodyMedium) } ?: Text("برای این کالا قیمت قابل اتکا ندارم.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionTitle("ویژگی‌ها")
        analysis.findings.forEach { finding ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(finding.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(finding.value ?: "نامعلوم", style = MaterialTheme.typography.bodyMedium, color = if (finding.value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (analysis.flags.isNotEmpty()) { SectionTitle("هشدارها"); analysis.flags.forEach { flag -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(12.dp)) { Text("${flag.severity} • ${flag.id}", style = MaterialTheme.typography.labelSmall); Text(flag.text, style = MaterialTheme.typography.bodyMedium) } } } }
        if (analysis.missing.isNotEmpty()) { SectionTitle("نامعلوم‌ها"); analysis.missing.forEach { BulletRow(it) } }
        if (analysis.citations.isNotEmpty()) { SectionTitle("شاهدها"); analysis.citations.forEach { citation -> Body("${citation.ref} — ${citation.label}") } }
        if (analysis.notes.isNotEmpty()) { SectionTitle("یادداشت"); analysis.notes.forEach { BulletRow(it) } }
    }
}
