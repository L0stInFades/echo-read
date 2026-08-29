package app.echoread.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.echoread.tts.SpeechApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** 更新清单（仓库 android/update.json，发布脚本生成） */
@Serializable
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    /** 备用下载地址（镜像），按顺序尝试 */
    val mirrors: List<String> = emptyList(),
    val notes: String = "",
    val sha256: String = "",
    val minSdk: Int = 26
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * 应用内更新（无后端）：清单走 jsDelivr CDN（国内可达）→ GitHub raw 兜底；
 * APK 挂 GitHub Release（可配镜像），下载后校验 SHA-256，再拉起系统安装器覆盖安装。
 */
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("echo-read-update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentVersionCode: Long by lazy {
        runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
    }
    val currentVersionName: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }.getOrDefault("?")
    }

    /** 检查更新；force=false 时 24 小时内只检查一次，且不打扰（静默） */
    suspend fun check(force: Boolean): UpdateState {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return _state.value
        val cur = _state.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Ready) return cur
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) {
            var lastErr: Exception? = null
            for (url in MANIFEST_URLS) {
                try {
                    val req = Request.Builder().url(url).header("Cache-Control", "no-cache").get().build()
                    SpeechApi.client.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                        val info = json.decodeFromString(UpdateInfo.serializer(), res.body?.string() ?: "")
                        return@withContext info
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: IllegalStateException("无法获取更新清单")
        }.let { runCatching { it } }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val info = result.getOrNull()
        val next = when {
            info == null -> UpdateState.Error("检查更新失败：${result.exceptionOrNull()?.message ?: "网络异常"}")
            info.versionCode > currentVersionCode && Build.VERSION.SDK_INT >= info.minSdk -> {
                // 本地已下载好同版本安装包则直接就绪
                val f = apkFile(info)
                if (f.isFile && f.length() > 0 && (info.sha256.isEmpty() || sha256(f).equals(info.sha256, ignoreCase = true))) UpdateState.Ready(info, f)
                else UpdateState.Available(info)
            }
            else -> UpdateState.UpToDate
        }
        _state.value = next
        return next
    }

    private fun apkFile(info: UpdateInfo): File = File(File(context.cacheDir, "updates").apply { mkdirs() }, "EchoRead-v${info.versionName}.apk")

    /** 下载安装包（带进度），校验 SHA-256 */
    suspend fun download(info: UpdateInfo): UpdateState {
        val target = apkFile(info)
        _state.value = UpdateState.Downloading(info, 0f)
        val result = withContext(Dispatchers.IO) {
            var lastErr: Exception? = null
            for (url in listOf(info.apkUrl) + info.mirrors) {
                try {
                    val req = Request.Builder().url(url).get().build()
                    // 首字节 12s 未到 / 传输中 20s 无进展即放弃当前地址换镜像（GitHub 直连在部分网络下会长时间挂起）
                    val client = SpeechApi.client.newBuilder()
                        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    client.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                        val body = res.body ?: throw IllegalStateException("空响应")
                        val total = body.contentLength()
                        val tmp = File(target.path + ".part")
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                var done = 0L
                                var lastEmit = 0L
                                while (input.read(buf).also { read = it } >= 0) {
                                    out.write(buf, 0, read)
                                    done += read
                                    val t = System.currentTimeMillis()
                                    if (total > 0 && t - lastEmit > 120) {
                                        lastEmit = t
                                        _state.value = UpdateState.Downloading(info, (done.toFloat() / total).coerceIn(0f, 0.99f))
                                    }
                                }
                            }
                        }
                        if (info.sha256.isNotEmpty() && !sha256(tmp).equals(info.sha256, ignoreCase = true)) {
                            tmp.delete()
                            throw IllegalStateException("安装包校验失败（SHA-256 不匹配）")
                        }
                        target.delete()
                        if (!tmp.renameTo(target)) throw IllegalStateException("无法保存安装包")
                        return@withContext target
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: IllegalStateException("下载失败")
        }.let { runCatching { it } }
        val next = result.getOrNull()?.let { UpdateState.Ready(info, it) }
            ?: UpdateState.Error("下载失败：${result.exceptionOrNull()?.message ?: "网络异常"}", info)
        _state.value = next
        return next
    }

    /** 拉起系统安装器；未授权「安装未知应用」时跳到系统设置页，返回 false */
    fun install(activity: Context, file: File): Boolean {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last-check"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        val MANIFEST_URLS = listOf(
            "https://cdn.jsdelivr.net/gh/L0stInFades/echo-read@main/android/update.json",
            "https://raw.githubusercontent.com/L0stInFades/echo-read/main/android/update.json"
        )
    }
}
