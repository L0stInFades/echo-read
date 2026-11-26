package app.echoread.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Discovery
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.VoiceProfile
import app.echoread.core.Voices
import app.echoread.core.i18n.S
import app.echoread.tts.SyncStatus
import app.echoread.ui.Chip
import app.echoread.ui.EchoIcons
import app.echoread.ui.EchoTextField
import app.echoread.ui.ErrorDetails
import app.echoread.ui.SearchField
import app.echoread.ui.TextAction
import app.echoread.ui.Toaster
import app.echoread.ui.echo

/* ---------------- 音色 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoicesPage(graph: AppGraph, tts: TtsSettings, p: VoiceProfile, d: Discovery?, status: SyncStatus?, onPicked: () -> Unit) {
    val c = echo
    val settings = graph.settings
    val spec = Providers.specOf(p, tts)
    val model = Providers.modelOf(spec, p)
    val openRouter = spec.id == Providers.OPENROUTER
    val hints = remember(model, openRouter) { if (openRouter) Voices.modelHints(model) else null }
    val freeVoice = hints?.freeVoice
    val catalog = remember(spec, p.model, p.apiKey, d) { Providers.voiceCatalog(spec, p, d) }
    val optional = Providers.voiceOptional(spec, model)
    val canRefresh = spec.listVoices.isNotEmpty() || spec.kind == ProviderSpec.KIND_GEMINI
    val current = p.voice

    var lang by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(catalog, q) {
        if (q.isEmpty()) catalog
        else catalog.filter { v -> v.id.lowercase().contains(q) || v.label.lowercase().contains(q) || v.note?.lowercase()?.contains(q) == true }
    }
    val groups = remember(filtered) { Voices.groupVoices(filtered) }
    LaunchedEffect(groups) { if (lang.isNotEmpty() && groups.none { it.lang == lang }) lang = "" }
    val shown = if (lang.isEmpty()) groups else groups.filter { it.lang == lang }

    fun pick(id: String) {
        settings.setVoice(p.id, id)
        onPicked()
    }

    Column(Modifier.fillMaxSize()) {
        val hint = when {
            freeVoice != null -> freeVoice.hint
            spec.voiceMode == ProviderSpec.VOICE_FREE -> S.providers.anyVoiceIdHint
            catalog.isEmpty() -> S.providers.noVoiceList
            else -> null
        }
        if (hint != null) {
            Text(hint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (catalog.size > 12) {
            SearchField(query, { query = it }, S.providers.searchVoices)
            Spacer(Modifier.height(10.dp))
        }
        if (groups.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(S.common.all, selected = lang.isEmpty()) { lang = "" }
                for (g in groups) Chip(g.label, selected = lang == g.lang, trailing = "${g.voices.size}") { lang = g.lang }
            }
            Spacer(Modifier.height(12.dp))
        }
        // 每个语种一项：屏幕外的语种不参与组合，几百个音色也不会一次性摆满
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (optional || freeVoice?.suggestions?.isNotEmpty() == true) {
                item("quick") {
                    FlowRow(
                        Modifier.padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (optional) Chip(S.providers.defaultVoice, selected = current.isEmpty()) { pick("") }
                        for (v in freeVoice?.suggestions.orEmpty()) {
                            Chip(v.label, selected = current == v.id, trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)) { pick(v.id) }
                        }
                    }
                }
            }
            items(shown, key = { it.lang }) { g ->
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    if (shown.size > 1) {
                        Text(g.label, color = c.text2, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in g.voices) {
                            Chip(
                                if (v.note != null) "${v.label} · ${v.note}" else v.label,
                                selected = current == v.id,
                                trailing = genderMark(v.gender), trailingColor = genderColor(v.gender)
                            ) { pick(v.id) }
                        }
                    }
                }
            }
            if (shown.isEmpty() && catalog.isNotEmpty()) {
                item("none") { Text(S.gemini.noMatchingVoices, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
            }
            if (canRefresh) {
                item("refresh") {
                    TextAction(
                        if (status is SyncStatus.Syncing) S.providers.connecting else S.providers.refreshList,
                        icon = EchoIcons.Refresh, modifier = Modifier.padding(start = 2.dp)
                    ) {
                        if (status !is SyncStatus.Syncing) graph.services.refresh(p.id) { st ->
                            if (st is SyncStatus.Failed) ErrorDetails.toast(st.error) else if (st is SyncStatus.Ok) Toaster.success(S.providers.listUpdated)
                        }
                    }
                }
            }
        }
        if (spec.voiceMode != ProviderSpec.VOICE_NONE) {
            Spacer(Modifier.height(12.dp))
            // 手填永远可用：复刻音色、音色库 ID、列表里还没有的新音色
            EchoTextField(
                current, { v -> settings.setVoice(p.id, v.trim()) }, label = S.settings.voiceId,
                placeholder = freeVoice?.placeholder ?: if (optional) S.settings.blankServiceDefault else spec.defaultVoice.ifBlank { S.settings.voiceId },
                mono = true
            )
        }
    }
}

internal fun genderMark(g: String?): String? = when (g) { "f" -> "♀"; "m" -> "♂"; else -> null }

@Composable
internal fun genderColor(g: String?): Color? = when (g) { "f" -> Color(0xFFF472B6); "m" -> Color(0xFF38BDF8); else -> null }
