package ir.aai.hushyar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * v0.12 — «اطلاعات هوش مصنوعی نباید خیلی تووی دید باشد؛ همه‌رو داخل یک باکس جا بده.»
 *
 * [Collapsible] همان باکس است: یک سرصفحهٔ کوتاه، و محتوا تنها وقتی باز می‌شود که کاربر خودش
 * بخواهد. هیچ اطلاعاتی حذف نشده — فقط دیگر پیش‌فرض بسته است.
 */
@Composable
fun Collapsible(
    title: String,
    subtitle: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { content() }
            }
        }
    }
}

/** A tappable one-line chip: suggestions, follow-ups, examples. */
@Composable
fun Chip(text: String, onClick: () -> Unit, selected: Boolean = false) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** Status pill used on the home screen instead of paragraphs of text. */
@Composable
fun StatusPill(label: String, value: String, tone: Color = MaterialTheme.colorScheme.secondary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = tone)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
