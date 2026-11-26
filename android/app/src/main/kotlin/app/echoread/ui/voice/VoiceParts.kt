package app.echoread.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.echoread.core.Hash
import app.echoread.core.ProviderSpec
import app.echoread.core.Providers
import app.echoread.core.TtsModelInfo
import app.echoread.core.VoiceInfo
import app.echoread.core.VoiceProfile
import app.echoread.core.i18n.S
import app.echoread.tts.SyncStatus
import app.echoread.tts.validBase
import app.echoread.ui.EchoIcons
import app.echoread.ui.LogoTile
import app.echoread.ui.Monogram
import app.echoread.ui.ProviderLogos
import app.echoread.ui.echo
import app.echoread.ui.warningColor

/* ---------------- 名字、头像、状态：各页共用 ---------------- */

internal fun serviceName(spec: ProviderSpec, p: VoiceProfile?): String = when {
    !p?.name.isNullOrBlank() -> p!!.name
    spec.id == Providers.CUSTOM -> S.providers.customEndpoint
    else -> spec.name
}

@Composable
private fun serviceColor(spec: ProviderSpec): Color {
    val dark = echo.isDark
    return remember(spec.id, spec.color, dark) {
        val parsed = runCatching { Color(android.graphics.Color.parseColor(spec.color)) }.getOrNull()
        when {
            // 纯黑 / 近黑的品牌色在深色界面上会消失：提亮成深灰
            parsed != null -> if (dark && parsed.red + parsed.green + parsed.blue < 0.45f) Color(0xFF3A3D45) else parsed
            else -> Color.hsl((Hash.cyrb53(spec.id).take(6).toLong(16) % 360).toFloat(), 0.5f, if (dark) 0.42f else 0.5f)
        }
    }
}

/** 服务的头像：目录里带了标志就画标志，否则（自定义端点、没有标志的服务商）是名字的首字母 */
@Composable
internal fun ServiceAvatar(spec: ProviderSpec, p: VoiceProfile?, size: Dp = 40.dp) {
    val tile = serviceColor(spec)
    val logo = remember(spec.logo) { ProviderLogos.vector(spec.logo) }
    if (logo != null) LogoTile(logo, tile, spec.logoScale, size)
    else Monogram(serviceName(spec, p), tile, size)
}

@Composable
internal fun SystemAvatar(size: Dp = 40.dp) {
    val c = echo
    Box(Modifier.size(size).background(c.fillStrong, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Icon(EchoIcons.Waves, null, tint = c.text2, modifier = Modifier.size(size * 0.5f))
    }
}

internal data class StatusLine(val dot: Color, val text: String, val textColor: Color, val detail: app.echoread.core.net.NetError? = null)

@Composable
internal fun statusLine(spec: ProviderSpec, p: VoiceProfile, st: SyncStatus?): StatusLine {
    val c = echo
    val ok = Color(0xFF34C759)
    return when {
        Providers.baseOf(spec, p).isEmpty() -> StatusLine(c.text3, S.providers.setAddress, c.accent)
        !Providers.validBase(Providers.baseOf(spec, p)) -> StatusLine(c.danger, S.providers.badAddress, c.danger)
        Providers.needsKey(spec, p) -> StatusLine(c.text3, S.providers.addApiKey, c.accent)
        st is SyncStatus.Syncing -> StatusLine(c.accent, S.providers.connecting, c.text2)
        st is SyncStatus.Failed -> StatusLine(c.danger, st.error.headline(), c.danger, st.error)
        st is SyncStatus.Ok -> {
            val warn = st.warning
            val parts = ArrayList<String>()
            parts += if (st.verified) S.providers.connected else S.providers.ready
            st.credits?.let { parts += S.providers.balance(String.format(java.util.Locale.ROOT, "%.2f", it.remaining)) }
            st.creditsError?.let { parts += S.providers.balanceFailed(it.badge()) }
            st.suspiciousAuth?.let { parts += S.providers.suspiciousAuth(it.badge()) }
            if (st.own > 0) parts += S.providers.ownVoices(st.own)
            StatusLine(if (warn != null) warningColor(c.isDark) else if (st.verified) ok else c.text3, parts.joinToString(" · "), c.text2, warn)
        }
        else -> StatusLine(c.text3, S.providers.ready, c.text2)
    }
}

@Composable
internal fun StatusRow(line: StatusLine, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(line.dot, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(line.text, color = line.textColor, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

internal fun badgeLine(badges: List<String>, extra: List<String> = emptyList()): String =
    (extra + badges.filter { it in Providers.BADGES }.map { S.providers.badge(it) }).joinToString(" · ")

internal fun modelLabel(options: List<TtsModelInfo>, model: String): String = when {
    model.isBlank() -> S.settings.notSelected
    else -> options.firstOrNull { it.id == model }?.name?.substringAfter(": ")?.ifEmpty { null } ?: model
}

internal fun voiceLabel(catalog: List<VoiceInfo>, voice: String, optional: Boolean): String {
    val v = voice.trim()
    if (v.isEmpty()) return if (optional) S.providers.defaultVoice else S.settings.notSet
    val info = catalog.firstOrNull { it.id == v } ?: return v
    return info.label + (info.note?.let { " · $it" } ?: "")
}

internal fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
