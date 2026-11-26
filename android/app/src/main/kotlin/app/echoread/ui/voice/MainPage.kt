package app.echoread.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Discovery
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsSettings
import app.echoread.core.i18n.S
import app.echoread.tts.AudioCache
import app.echoread.tts.SyncStatus
import app.echoread.tts.ready
import app.echoread.tts.test
import app.echoread.ui.ChevronEnd
import app.echoread.ui.Chip
import app.echoread.ui.EchoIcons
import app.echoread.ui.EchoTextField
import app.echoread.ui.ErrorDetails
import app.echoread.ui.GradientButton
import app.echoread.ui.GroupRadius
import app.echoread.ui.Hairline
import app.echoread.ui.SettingsSection
import app.echoread.ui.SettingsSlider
import app.echoread.ui.SoftButton
import app.echoread.ui.TextAction
import app.echoread.ui.Toaster
import app.echoread.ui.echo
import app.echoread.ui.formatBytes
import app.echoread.ui.motion.echoPress
import app.echoread.ui.warningColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ---------------- 主页 ---------------- */

/** 语气快捷项。值用英文：各家的官方示例都是英文短语；中文同样能用，用户可以直接在输入框里改 */
private val STYLE_PRESETS: List<Pair<String, String>> get() = listOf(
    S.gemini.styleNatural to "",
    S.gemini.styleCalm to "calm and relaxed",
    S.common.warm to "warm and gentle",
    S.gemini.styleStorytelling to "warm, unhurried storytelling",
    S.gemini.styleSlow to "speaking slowly"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainPage(
    graph: AppGraph,
    tts: TtsSettings,
    discovery: Map<String, Discovery>,
    statuses: Map<String, SyncStatus>,
    onOpen: (VoicePage) -> Unit
) {
    val c = echo
    val scope = rememberCoroutineScope()
    val settings = graph.settings
    val appContext = LocalContext.current.applicationContext
    val system = tts.usesSystemVoice
    val p = tts.activeProfile
    val spec = p?.let { Providers.specOf(it, tts) }

    var previewing by remember { mutableStateOf(false) }
    var previewLabel by remember { mutableStateOf("") }
    var cacheStats by remember { mutableStateOf<AudioCache.Stats?>(null) }
    LaunchedEffect(Unit) { cacheStats = graph.audioCache.stats() }

    fun preview() {
        val profile = p ?: return
        val sp = spec ?: return
        if (Providers.needsKey(sp, profile)) {
            Toaster.error(S.settings.enterApiKeyFirst)
            onOpen(VoicePage.Service(profile.id))
            return
        }
        previewing = true
        previewLabel = ""
        // 入口快照：合成期间改了设置，试的仍是按下按钮那一刻的配置
        scope.launch {
            val r = Providers.test(profile, tts)
            if (!r.ok || r.audio == null) {
                previewing = false
                r.error?.let { ErrorDetails.toast(it) } ?: Toaster.error(r.message)
                return@launch
            }
            // 试听就得真的响：合成成功后立刻播出来（占用同一个播放器，先暂停正在朗读的引擎）
            previewLabel = S.settings.playing
            try {
                graph.engine.pause()
                val f = withContext(Dispatchers.IO) {
                    File(appContext.cacheDir, "preview-${System.currentTimeMillis()}.audio").also { it.writeBytes(r.audio) }
                }
                graph.playback.setActive(true)
                val h = graph.playback.play(f, 1f, deleteAfter = true)
                // 面板关掉 / 切到别的子页时协程被取消：试听当场停下，不能交还焦点之后还在后台接着说
                try {
                    h.awaitEnded()
                } finally {
                    h.stop()
                }
            } catch (e: CancellationException) {
                // 被取消不是播放失败，不弹错误提示
                throw e
            } catch (e: Exception) {
                Toaster.error(e.message ?: S.common.playbackFailed)
            } finally {
                graph.playback.setActive(false)
                previewing = false
                previewLabel = ""
            }
        }
    }

    PageColumn {
        /* ---- 当前服务：状态即入口，点开就是「朗读服务」 ---- */
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(GroupRadius)).background(c.fill)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .echoPress(pressedScale = 1f, pressedOverlay = c.text.copy(alpha = 0.07f)) { onOpen(VoicePage.Services) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (system || p == null || spec == null) SystemAvatar(44.dp) else ServiceAvatar(spec, p, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (system || p == null || spec == null) S.providers.systemVoice else serviceName(spec, p),
                        color = c.text, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (system || p == null || spec == null) {
                        Text(S.providers.systemVoiceDesc, color = c.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else {
                        StatusRow(statusLine(spec, p, statuses[p.id]))
                    }
                }
                Spacer(Modifier.width(8.dp))
                ChevronEnd()
            }
            if (!system && p != null && spec != null) {
                val line = statusLine(spec, p, statuses[p.id])
                Hairline(start = 16.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!Providers.ready(spec, p)) {
                        // 还用不了：这里唯一有意义的动作是去把 Key 填上
                        GradientButton(S.providers.enterKey, Modifier.weight(1f), icon = EchoIcons.Key, height = 42.dp) { onOpen(VoicePage.Service(p.id)) }
                    } else {
                        // 试听验证的就是这条连接，所以紧挨着状态
                        SoftButton(
                            if (previewing) previewLabel.ifEmpty { S.common.generating } else S.common.preview,
                            Modifier.weight(1f), color = c.accent, height = 40.dp, icon = if (previewing) null else EchoIcons.PlaySmall
                        ) { if (!previewing) preview() }
                    }
                    if (line.detail != null) {
                        SoftButton(S.common.details, Modifier.weight(1f), color = c.danger, height = 40.dp) { ErrorDetails.show(line.detail) }
                    }
                }
                if (spec.sunset.isNotBlank()) {
                    Text(
                        S.providers.retiresOn(spec.sunset), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        if (!system && p != null && spec != null) {
            val d = discovery[p.id]
            val model = Providers.modelOf(spec, p)
            val options = remember(spec, d) { Providers.modelOptions(spec, d) }
            val catalog = remember(spec, p.model, p.apiKey, d) { Providers.voiceCatalog(spec, p, d) }
            val optional = Providers.voiceOptional(spec, model)
            // 按模板发请求、目录里又没列模型的服务商（Azure、xAI…）：模型由音色决定，不摆这一行
            val showModel = options.isNotEmpty() || spec.kind != ProviderSpec.KIND_HTTP
            val showStyle = Providers.supportsStyle(spec, model)

            /* ---- 声音：行尾就是当前值，一眼扫完全部配置 ---- */
            SettingsSection(S.providers.sound) {
                if (showModel) row(S.common.model, end = modelLabel(options, model), trailing = { ChevronEnd() }, onClick = { onOpen(VoicePage.Models) })
                if (spec.voiceMode != ProviderSpec.VOICE_NONE) {
                    row(S.common.voice, end = voiceLabel(catalog, p.voice, optional), trailing = { ChevronEnd() }, onClick = { onOpen(VoicePage.Voices) })
                }
                if (showStyle) {
                    custom {
                        Text(S.providers.style, color = c.text, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        EchoTextField(p.style, { v -> settings.updateProfile(p.id) { it.copy(style = v) } }, placeholder = S.providers.stylePlaceholder)
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((label, value) in STYLE_PRESETS) {
                                Chip(label, selected = p.style.trim() == value) { settings.updateProfile(p.id) { it.copy(style = value) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        SettingsSection(S.providers.playback) {
            custom {
                SettingsSlider(
                    S.common.speed, String.format(java.util.Locale.ROOT, "%.2f×", tts.rate),
                    tts.rate, 0.5f..2.5f, steps = 39
                ) { v -> settings.updateTts { it.copy(rate = (Math.round(v * 20) / 20f)) } }
            }
        }

        if (!system && p != null && spec != null) {
            Spacer(Modifier.height(22.dp))
            val cap = Providers.maxChars(spec, Providers.modelOf(spec, p))
            /* ---- 高级：影响成本与延迟的旋钮，以及缓存 ---- */
            SettingsSection(S.settings.advanced) {
                custom {
                    SettingsSlider(S.settings.charactersPerSegment, "${tts.maxChunkChars}", tts.maxChunkChars.toFloat(), 80f..400f, steps = 31) { v ->
                        settings.updateTts { it.copy(maxChunkChars = (Math.round(v / 10) * 10)) }
                    }
                    Text(S.settings.chunkHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                    if (cap in 1 until tts.maxChunkChars) {
                        Spacer(Modifier.height(4.dp))
                        Text(S.providers.limitPerRequest(cap), color = warningColor(c.isDark), style = MaterialTheme.typography.bodySmall)
                    }
                }
                custom {
                    SettingsSlider(S.settings.prefetchSegments, "${tts.prefetch}", tts.prefetch.toFloat(), 0f..5f, steps = 4) { v ->
                        settings.updateTts { it.copy(prefetch = Math.round(v)) }
                    }
                    Text(S.settings.prefetchHint, color = c.text3, style = MaterialTheme.typography.bodySmall)
                }
                row(
                    S.settings.audioCache,
                    value = cacheStats?.let { S.settings.cacheStats(it.count, formatBytes(it.bytes)) } ?: S.settings.calculating,
                    trailing = {
                        TextAction(S.settings.clear, color = c.danger) {
                            scope.launch {
                                graph.audioCache.clear()
                                cacheStats = graph.audioCache.stats()
                                Toaster.success(S.settings.audioCacheCleared)
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
