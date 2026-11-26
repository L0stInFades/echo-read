package app.echoread.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.i18n.S
import app.echoread.tts.SyncStatus
import app.echoread.tts.ready
import app.echoread.ui.CheckBadge
import app.echoread.ui.EchoIcons
import app.echoread.ui.EchoTextField
import app.echoread.ui.ErrorDetails
import app.echoread.ui.GradientButton
import app.echoread.ui.SettingsSection
import app.echoread.ui.SoftButton
import app.echoread.ui.TextAction
import app.echoread.ui.Toaster
import app.echoread.ui.echo
import app.echoread.ui.motion.EchoTransitions
import app.echoread.ui.warningColor
import kotlinx.coroutines.delay

/* ---------------- 服务设置：Key、地址、连接检查 ---------------- */

/** 停手这么久才落盘 */
private const val KEY_SAVE_DELAY_MS = 500L

/**
 * Key / 密钥输入框：框里的字即时响应，落盘推迟到停手片刻、失焦或离开页面时。
 * 每次落盘都要走一遍硬件 Keystore 加密、再把整份设置 JSON 写进 SharedPreferences ——
 * 逐键落盘既拖慢输入，也会把敲到一半的每个前缀都加密、缓存一遍。
 */
@Composable
private fun KeyField(value: String, onSave: (String) -> Unit, label: String, placeholder: String) {
    var text by remember { mutableStateOf(value) }
    var dirty by remember { mutableStateOf(false) }
    val save by rememberUpdatedState(onSave)
    fun flush() {
        if (!dirty) return
        dirty = false
        save(text)
    }
    // 别处改了这个值（外部传入配置、刚落盘回来的规整结果）且框里没有未落盘的输入时，跟上它
    LaunchedEffect(value) { if (!dirty) text = value }
    LaunchedEffect(text, dirty) {
        if (!dirty) return@LaunchedEffect
        delay(KEY_SAVE_DELAY_MS)
        flush()
    }
    DisposableEffect(Unit) { onDispose { flush() } }
    EchoTextField(
        text, { text = it; dirty = true }, label = label, placeholder = placeholder, password = true, mono = true,
        modifier = Modifier.onFocusChanged { if (!it.hasFocus) flush() }
    )
}

@Composable
internal fun ServicePage(
    graph: AppGraph,
    tts: TtsSettings,
    id: String,
    status: SyncStatus?,
    onRemoved: () -> Unit,
    onUse: () -> Unit
) {
    val c = echo
    val settings = graph.settings
    val uriHandler = LocalUriHandler.current
    val p = tts.profiles.firstOrNull { it.id == id } ?: return
    val spec = Providers.specOf(p, tts)
    val ownAddress = spec.baseUrl.isBlank()
    var showAddress by remember(id) { mutableStateOf(p.baseUrl.isNotBlank()) }

    // 改了 Key / 地址 / 字段：稍等片刻自动检查一次连接
    LaunchedEffect(id, p.apiKey, p.baseUrl, p.fields) {
        delay(900)
        graph.services.ensureFresh(id)
    }

    PageColumn {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceAvatar(spec, p, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val regionLabel = when (spec.region) { "cn" -> S.providers.regionCn; "local" -> S.providers.regionLocal; else -> S.providers.regionGlobal }
                Text(
                    badgeLine(spec.badges, listOf(regionLabel)), color = c.text2, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (spec.keyUrl.isNotBlank()) TextAction(S.providers.getApiKey, icon = EchoIcons.ChevronRight) { runCatching { uriHandler.openUri(spec.keyUrl) } }
                    if (spec.docsUrl.isNotBlank()) TextAction(S.providers.apiDocs, icon = EchoIcons.ChevronRight) { runCatching { uriHandler.openUri(spec.docsUrl) } }
                }
            }
        }
        if (spec.sunset.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(S.providers.retiresOn(spec.sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))

        SettingsSection(footer = S.providers.keyStaysOnDevice) {
            custom {
                if (ownAddress) {
                    EchoTextField(p.name, { v -> settings.updateProfile(id) { it.copy(name = v) } }, label = S.providers.displayName, placeholder = serviceName(spec, null))
                    Spacer(Modifier.height(12.dp))
                    EchoTextField(
                        p.baseUrl, { v -> settings.updateProfile(id) { it.copy(baseUrl = Providers.cleanAddress(v)) } },
                        label = S.providers.address, placeholder = spec.baseHint.ifBlank { "https://" }, keyboardType = KeyboardType.Uri, mono = true
                    )
                    Spacer(Modifier.height(12.dp))
                }
                KeyField(p.apiKey, { v -> settings.updateProfile(id) { it.copy(apiKey = Providers.cleanSecret(v)) } }, S.providers.apiKey, spec.keyHint)
                for (f in spec.fields) {
                    Spacer(Modifier.height(12.dp))
                    val value = p.fields[f.id] ?: f.default
                    val save = { v: String -> settings.updateProfile(id) { it.copy(fields = it.fields + (f.id to Providers.cleanSecret(v))) } }
                    if (f.secret) KeyField(value, save, S.providers.fieldLabel(f.id), f.hint.ifBlank { f.default })
                    else EchoTextField(value, save, label = S.providers.fieldLabel(f.id), placeholder = f.hint.ifBlank { f.default }, mono = true)
                }
                if (!ownAddress) {
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(showAddress, enter = EchoTransitions.expandIn, exit = EchoTransitions.collapseOut) {
                        EchoTextField(
                            p.baseUrl, { v -> settings.updateProfile(id) { it.copy(baseUrl = Providers.cleanAddress(v)) } },
                            label = S.providers.addressOverride, placeholder = spec.baseUrl, keyboardType = KeyboardType.Uri, mono = true,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    if (!showAddress) TextAction(S.providers.addressOverride, color = c.text2) { showAddress = true }
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        SettingsSection {
            custom {
                val line = statusLine(spec, p, status)
                StatusRow(line, Modifier.fillMaxWidth(), maxLines = 3)
                if (status is SyncStatus.Ok && !status.verified) {
                    Spacer(Modifier.height(6.dp))
                    Text(S.providers.notVerifiedHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(
                        if (status is SyncStatus.Syncing) S.providers.connecting else S.providers.checkConnection,
                        Modifier.weight(1f), color = c.accent, height = 40.dp, icon = EchoIcons.Refresh,
                        enabled = Providers.ready(spec, p) && status !is SyncStatus.Syncing
                    ) {
                        graph.services.refresh(id) { st -> if (st is SyncStatus.Failed) ErrorDetails.toast(st.error) }
                    }
                    if (line.detail != null) {
                        SoftButton(S.common.details, Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(line.detail) }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        if (tts.active == id) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CheckBadge(18.dp)
                Spacer(Modifier.width(8.dp))
                Text(S.providers.inUse, color = c.text2, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            GradientButton(S.providers.useThisService, Modifier.fillMaxWidth(), enabled = Providers.ready(spec, p)) {
                if (Providers.ready(spec, p)) {
                    settings.setActive(id)
                    onUse()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction(S.providers.removeService, color = c.danger) {
                graph.services.forget(id)
                settings.removeProfile(id)
                Toaster.show(S.providers.serviceRemoved)
                onRemoved()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
