package app.echoread.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Discovery
import app.echoread.core.Providers
import app.echoread.core.TtsModelInfo
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.Voices
import app.echoread.core.i18n.S
import app.echoread.ui.CheckBadge
import app.echoread.ui.Chip
import app.echoread.ui.EchoTextField
import app.echoread.ui.SearchField
import app.echoread.ui.echo
import app.echoread.ui.motion.echoPress
import app.echoread.ui.warningColor

/* ---------------- 模型 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsPage(graph: AppGraph, tts: TtsSettings, p: VoiceProfile, d: Discovery?, onPicked: () -> Unit) {
    val c = echo
    val settings = graph.settings
    val spec = Providers.specOf(p, tts)
    val current = Providers.modelOf(spec, p)
    val options = remember(spec, d) { Providers.modelOptions(spec, d) }
    val openRouter = spec.id == Providers.OPENROUTER
    var filter by remember { mutableStateOf("") }
    val q = filter.trim().lowercase()

    fun metaOf(m: TtsModelInfo): String = if (openRouter) Voices.formatModelMeta(m, m.id) else {
        val ms = spec.models.firstOrNull { it.id == m.id }
        badgeLine(ms?.badges.orEmpty(), listOfNotNull(ms?.langs?.takeIf { it > 0 }?.let { S.providers.languages(it) }))
    }

    val ordered = remember(options, q, openRouter) {
        val recommended = if (openRouter) Voices.RECOMMENDED_MODELS.map { it.id } else emptyList()
        val list = if (openRouter) options.sortedWith(compareBy<TtsModelInfo> { val i = recommended.indexOf(it.id); if (i < 0) 99 else i }.thenBy { it.id }) else options
        if (q.isEmpty()) list else list.filter { m ->
            m.id.lowercase().contains(q) || m.name.lowercase().contains(q) || metaOf(m).lowercase().contains(q) ||
                ((q == "free" || q == "免费") && m.id.contains(":free"))
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (options.size > 8) {
            SearchField(filter, { filter = it }, S.providers.searchModels)
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (options.isEmpty() && openRouter) {
                item("hint") {
                    Column {
                        Text(S.settings.noModelsSyncedHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in Voices.RECOMMENDED_MODELS) {
                                Chip(r.label, selected = current == r.id, trailing = r.tag) { settings.setModel(p.id, r.id); onPicked() }
                            }
                        }
                    }
                }
            }
            items(ordered, key = { it.id }) { m ->
                val selected = m.id == current
                val meta = metaOf(m)
                val sunset = spec.models.firstOrNull { it.id == m.id }?.sunset.orEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.fill)
                        .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { settings.setModel(p.id, m.id); onPicked() }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name.substringAfter(": ").ifEmpty { m.name },
                            color = c.text, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (meta.isNotEmpty()) Text(meta, color = c.text2, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (sunset.isNotBlank()) Text(S.providers.retiresOn(sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall)
                        Text(m.id, color = c.text3, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) {
                        Spacer(Modifier.width(10.dp))
                        CheckBadge()
                    }
                }
            }
            if (ordered.isEmpty() && options.isNotEmpty()) {
                item("none") { Text(S.settings.noMatchingModels, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 列表里没有的模型永远可以手填：新模型上线不必等目录更新
        EchoTextField(p.model, { v -> settings.setModel(p.id, v.trim()) }, label = S.providers.modelId, placeholder = spec.defaultModel.ifBlank { S.settings.modelIdPlaceholder }, mono = true)
    }
}
