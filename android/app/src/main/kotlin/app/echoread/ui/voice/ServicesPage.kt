package app.echoread.ui.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Discovery
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.i18n.S
import app.echoread.tts.SyncStatus
import app.echoread.tts.ready
import app.echoread.tts.validBase
import app.echoread.ui.CheckBadge
import app.echoread.ui.EchoIcons
import app.echoread.ui.GradientButton
import app.echoread.ui.IconButtonEcho
import app.echoread.ui.SettingsSection
import app.echoread.ui.echo

/* ---------------- 朗读服务：已添加的，点一下切换 ---------------- */

@Composable
internal fun ServicesPage(
    graph: AppGraph,
    tts: TtsSettings,
    discovery: Map<String, Discovery>,
    statuses: Map<String, SyncStatus>,
    onOpen: (VoicePage) -> Unit
) {
    val c = echo
    val settings = graph.settings
    PageColumn {
        SettingsSection(S.providers.yourServices) {
            for (p in tts.profiles) {
                val spec = Providers.specOf(p, tts)
                val ready = Providers.ready(spec, p)
                val selected = tts.active == p.id
                val subtitle = when {
                    Providers.baseOf(spec, p).isEmpty() -> S.providers.setAddress
                    !Providers.validBase(Providers.baseOf(spec, p)) -> S.providers.badAddress
                    Providers.needsKey(spec, p) -> S.providers.addApiKey
                    else -> modelLabel(Providers.modelOptions(spec, discovery[p.id]), Providers.modelOf(spec, p)).takeIf { Providers.modelOf(spec, p).isNotBlank() }
                        ?: spec.baseUrl.ifBlank { p.baseUrl }.removePrefix("https://").removePrefix("http://").substringBefore('/')
                }
                row(
                    serviceName(spec, p),
                    value = subtitle,
                    valueColor = if (!ready) c.accent else if (statuses[p.id] is SyncStatus.Failed) c.danger else null,
                    leading = { ServiceAvatar(spec, p) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) CheckBadge()
                            IconButtonEcho(EchoIcons.Settings, S.providers.configure, size = 34.dp, iconSize = 18.dp, touchSize = 44.dp) { onOpen(VoicePage.Service(p.id)) }
                        }
                    },
                    // 还不能用的（没填 Key）点了是去填，而不是切过去对着一个报错发呆
                    onClick = { if (ready) settings.setActive(p.id) else onOpen(VoicePage.Service(p.id)) }
                )
            }
            row(
                S.providers.systemVoice,
                value = S.providers.systemVoiceDesc,
                leading = { SystemAvatar() },
                trailing = { if (tts.usesSystemVoice) CheckBadge() },
                onClick = { settings.setActive(TtsSettings.SYSTEM) }
            )
        }
        Spacer(Modifier.height(16.dp))
        GradientButton(S.providers.addService, Modifier.fillMaxWidth(), icon = EchoIcons.Plus) { onOpen(VoicePage.Add) }
        Spacer(Modifier.height(8.dp))
    }
}
