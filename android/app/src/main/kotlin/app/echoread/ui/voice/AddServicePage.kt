package app.echoread.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.i18n.S
import app.echoread.ui.ChevronEnd
import app.echoread.ui.EchoIcons
import app.echoread.ui.SearchField
import app.echoread.ui.SettingsSection
import app.echoread.ui.echo

/* ---------------- 添加服务：内置目录 ---------------- */

@Composable
internal fun AddServicePage(graph: AppGraph, tts: TtsSettings, onAdded: (String) -> Unit, onImport: () -> Unit) {
    val c = echo
    val settings = graph.settings
    var query by remember { mutableStateOf("") }
    val now = remember { today() }
    val q = query.trim().lowercase()
    val all = remember(tts.customSpecs, now) { (Providers.builtIn + tts.customSpecs).filterNot { Providers.retired(it, now) } }
    val shown = remember(all, q) {
        if (q.isEmpty()) all else all.filter { s ->
            s.name.lowercase().contains(q) || s.id.contains(q) || s.models.any { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
        }
    }

    fun add(spec: ProviderSpec) {
        val multi = spec.id == Providers.CUSTOM || spec.baseUrl.isBlank()
        val existing = tts.profiles.firstOrNull { it.spec == spec.id }
        onAdded(if (existing != null && !multi) existing.id else settings.addProfile(spec).id)
    }

    PageColumn {
        SearchField(query, { query = it }, S.providers.searchServices)
        Spacer(Modifier.height(18.dp))
        val groups = listOf("global" to S.providers.regionGlobal, "cn" to S.providers.regionCn, "local" to S.providers.regionLocal)
        var any = false
        for ((region, label) in groups) {
            val list = shown.filter { it.region == region }
            val withImport = region == "local" && q.isEmpty()
            if (list.isEmpty() && !withImport) continue
            any = true
            SettingsSection(label) {
                for (spec in list) {
                    val added = spec.id != Providers.CUSTOM && spec.baseUrl.isNotBlank() && tts.profiles.any { it.spec == spec.id }
                    val custom = spec.id == Providers.CUSTOM
                    row(
                        if (custom) S.providers.customEndpoint else spec.name,
                        value = (if (custom) S.providers.customEndpointDesc else badgeLine(spec.badges)).ifBlank { null },
                        leading = { ServiceAvatar(spec, null) },
                        trailing = {
                            if (added) Text(S.providers.added, color = c.text3, style = MaterialTheme.typography.labelMedium)
                            else Icon(EchoIcons.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        },
                        onClick = { add(spec) }
                    )
                }
                if (withImport) {
                    row(
                        S.providers.pasteDefinition,
                        value = S.providers.pasteDefinitionDesc,
                        leading = {
                            Box(Modifier.size(40.dp).background(c.fillStrong, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(EchoIcons.FileText, null, tint = c.text2, modifier = Modifier.size(20.dp))
                            }
                        },
                        trailing = { ChevronEnd() },
                        onClick = onImport
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (!any) {
            Text(S.providers.noServicesMatch, color = c.text3, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}
