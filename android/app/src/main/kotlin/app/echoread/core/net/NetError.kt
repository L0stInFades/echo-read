package app.echoread.core.net

import app.echoread.core.i18n.S
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 网络错误模型（纯 Kotlin，不依赖 okhttp / android，可在 JVM 单测里直接构造）。
 *
 * 设计要点，全部来自 0.1.x 的实测教训：
 * ① [status] 可空，且**只**存真实响应码。本地前置校验（没填 Key/Base URL）绝不伪造 400/401，
 *    「返回音频为空」也不再伪造 502 —— 否则「4xx 还是 5xx」这个判断本身就不可信；
 * ② 服务商自己的话（[providerMessage]）与我们的话（[headline]）分开存，永不互相覆盖。
 *    旧实现在 401/402/404/429 这四个最该说清楚原因的状态上把服务商原文丢掉了；
 * ③ 分类（[category]）与处置（[disposition]）分离：同一个 400，可能该跳过这一段继续读，
 *    也可能该停掉整本；由 [disposition] 一处决定，不再散落在各个 catch 里。
 */
enum class NetCategory {
    /** 401 / 鉴权型 403：Key 无效、过期、被封 */
    AUTH,
    /** 402：额度耗尽 */
    QUOTA,
    /** 429：限流 */
    RATE_LIMIT,
    /** 400 / 413 / 422 / 451：这次请求本身不被接受 */
    BAD_REQUEST,
    /**
     * 400 FAILED_PRECONDITION：账户或地区层面的前置条件不满足（Gemini API 不对所在地区开放、
     * 项目未开通结算…）。与 [BAD_REQUEST] 分开：换下一段文字照样失败，跳段只会把整本书跳空
     */
    PRECONDITION,
    /** 404：端点或模型不存在 */
    NOT_FOUND,
    /** 5xx */
    SERVER,
    /** 连接/读取超时 */
    TIMEOUT,
    /** DNS 失败、连不上、链路断 */
    CONNECTIVITY,
    /** TLS 握手/证书失败 */
    TLS,
    /** HTTP 成功但响应体不是预期格式 */
    PARSE,
    /** HTTP 200 但音频字节为空 */
    EMPTY_AUDIO,
    CONFIG_MISSING_KEY,
    CONFIG_MISSING_BASE_URL,
    UNKNOWN
}

/** 状态码大类。[NONE] = 根本没拿到响应，与 4xx/5xx 是三种可见地不同的形态 */
enum class StatusClass { CLIENT, SERVER, NONE }

/** 调用种类，决定文案口径与超时预算 */
enum class CallKind { SYNTHESIS, MODEL_LIST, CREDITS, UPDATE_MANIFEST, UPDATE_APK }

/** 处置方式：重试 / 跳过这一段 / 停掉整个会话 */
enum class Disposition { RETRY, SKIP_SEGMENT, STOP_SESSION }

/** 设备侧链路状态。core 只声明，实现在 data 层（ConnectivityManager），保持 core 无 android 依赖 */
interface NetworkStatus {
    /** 是否存在可用链路。未知时返回 true（宁可重试，也不要误报「无网络」） */
    fun online(): Boolean = true
}

data class NetError(
    val category: NetCategory,
    /** 只可能是真实响应码；null 表示没有拿到任何响应 */
    val status: Int? = null,
    /** 服务商自己的错误描述，原样保留，不改写 */
    val providerMessage: String? = null,
    /** 服务商的 error.code / error.type */
    val providerCode: String? = null,
    /** 已去掉 query 的端点，如 https://openrouter.ai/api/v1/audio/speech */
    val endpoint: String = "",
    val method: String = "",
    val kind: CallKind = CallKind.SYNTHESIS,
    val model: String? = null,
    /** 服务商要求的等待秒数（Retry-After） */
    val retryAfterSec: Long? = null,
    /** 响应体片段，≤512 字节且已脱敏 */
    val bodySnippet: String? = null,
    /** 传输层异常的简单类名；HTTP 错误为 null */
    val transport: String? = null,
    val attempt: Int = 1,
    val maxAttempts: Int = 1,
    val elapsedMs: Long = 0
) {
    val statusClass: StatusClass
        get() = when (status) {
            null -> StatusClass.NONE
            in 400..499 -> StatusClass.CLIENT
            in 500..599 -> StatusClass.SERVER
            else -> StatusClass.NONE
        }

    /** 是否本地前置校验失败（压根没发出请求） */
    val isConfig: Boolean
        get() = category == NetCategory.CONFIG_MISSING_KEY || category == NetCategory.CONFIG_MISSING_BASE_URL

    /** 是否 Google 的 Gemini API（建议文案因此不同：没有 Base URL 可改，配额按天重置） */
    val isGoogleApi: Boolean
        get() = hostOf(endpoint).let { it == "generativelanguage.googleapis.com" || it.endsWith(".googleapis.com") }

    /** 状态徽标：一眼区分 4xx / 5xx / 无响应 —— 这是「报错要展现出来」的最小单位 */
    fun badge(): String = when {
        isConfig -> S.errors.badgeNotSet
        status != null -> status.toString()
        else -> S.errors.badgeNoResponse
    }

    /**
     * 一行标题，**必定**包含状态码或「无响应」。给 dock 状态行与 toast 用。
     * 注意：这里只说「是什么」，服务商原话放进 [detail]，避免一行挤爆。
     */
    fun headline(): String {
        val host = hostOf(endpoint)
        return when (category) {
            NetCategory.CONFIG_MISSING_KEY -> S.errors.apiKeyNotSet
            NetCategory.CONFIG_MISSING_BASE_URL -> S.errors.baseUrlNotSet
            NetCategory.AUTH -> S.errors.apiKeyRejected(badge())
            NetCategory.QUOTA -> S.errors.outOfQuota(badge())
            NetCategory.RATE_LIMIT ->
                S.errors.rateLimited(badge()) +
                    (retryAfterSec?.let { S.errors.retryAfterSuffix(it) } ?: "")
            NetCategory.NOT_FOUND -> S.errors.notFound(badge())
            NetCategory.BAD_REQUEST -> S.errors.requestRejected(badge())
            NetCategory.PRECONDITION -> S.errors.precondition(badge())
            NetCategory.SERVER -> S.errors.providerError(badge())
            NetCategory.TIMEOUT -> S.errors.requestTimedOut
            NetCategory.CONNECTIVITY ->
                if (host.isEmpty()) S.errors.networkConnectionFailed
                else S.errors.cantReach(host)
            NetCategory.TLS -> S.errors.secureConnectionFailed
            NetCategory.PARSE -> S.errors.unreadableResponse(badge())
            NetCategory.EMPTY_AUDIO -> S.errors.emptyAudioReturned(badge())
            NetCategory.UNKNOWN -> S.errors.requestFailed(badge())
        }
    }

    /** 用户能据此行动的一句话建议 */
    fun advice(): String = when (category) {
        NetCategory.CONFIG_MISSING_KEY -> S.errors.adviceMissingKey
        NetCategory.CONFIG_MISSING_BASE_URL -> S.errors.adviceMissingBaseUrl
        NetCategory.AUTH -> S.errors.adviceAuth
        NetCategory.QUOTA ->
            if (isGoogleApi) S.errors.adviceGeminiQuota
            else S.errors.adviceQuota
        NetCategory.RATE_LIMIT ->
            if (isGoogleApi) S.errors.adviceGeminiRateLimit
            else S.errors.adviceRateLimit
        NetCategory.NOT_FOUND ->
            if (isGoogleApi) S.errors.adviceGeminiNotFound
            else S.errors.adviceNotFound
        NetCategory.BAD_REQUEST -> S.errors.adviceBadRequest
        NetCategory.PRECONDITION -> S.errors.advicePrecondition
        NetCategory.SERVER -> S.errors.adviceServer
        NetCategory.TIMEOUT -> S.errors.adviceTimeout
        NetCategory.CONNECTIVITY -> S.errors.adviceConnectivity
        NetCategory.TLS -> S.errors.adviceTls
        NetCategory.PARSE -> S.errors.adviceParse
        NetCategory.EMPTY_AUDIO -> S.errors.adviceEmptyAudio
        NetCategory.UNKNOWN -> S.errors.adviceUnknown
    }

    /** 多行详情（详情面板 / 复制到剪贴板），已脱敏 */
    fun detail(): String = buildString {
        appendLine(headline())
        appendLine()
        providerMessage?.takeIf { it.isNotBlank() }?.let {
            appendLine(S.errors.detailProviderMessage(it))
            providerCode?.takeIf { c -> c.isNotBlank() }?.let { c -> appendLine(S.errors.detailProviderCode(c)) }
            appendLine()
        }
        appendLine(S.errors.detailCategory(categoryLabel(), statusClassLabel()))
        if (endpoint.isNotEmpty()) appendLine(S.errors.detailEndpoint(method, endpoint))
        model?.takeIf { it.isNotBlank() }?.let { appendLine(S.errors.detailModel(it)) }
        transport?.let { appendLine(S.errors.detailTransport(it)) }
        if (maxAttempts > 1) appendLine(S.errors.detailAttempt(attempt, maxAttempts))
        if (elapsedMs > 0) appendLine(S.errors.detailElapsed(elapsedMs))
        retryAfterSec?.let { appendLine("Retry-After：${it}s") }
        bodySnippet?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(S.errors.detailBody)
            appendLine(it)
        }
        appendLine()
        append(S.errors.detailSuggestion(advice()))
    }

    fun categoryLabel(): String = when (category) {
        NetCategory.AUTH -> S.errors.catAuth
        NetCategory.QUOTA -> S.errors.catQuota
        NetCategory.RATE_LIMIT -> S.errors.catRateLimit
        NetCategory.BAD_REQUEST -> S.errors.catBadRequest
        NetCategory.PRECONDITION -> S.errors.catPrecondition
        NetCategory.NOT_FOUND -> S.errors.catNotFound
        NetCategory.SERVER -> S.errors.catServer
        NetCategory.TIMEOUT -> S.errors.catTimeout
        NetCategory.CONNECTIVITY -> S.errors.catConnectivity
        NetCategory.TLS -> S.errors.catTls
        NetCategory.PARSE -> S.errors.catParse
        NetCategory.EMPTY_AUDIO -> S.errors.catEmptyAudio
        NetCategory.CONFIG_MISSING_KEY -> S.errors.catMissingKey
        NetCategory.CONFIG_MISSING_BASE_URL -> S.errors.catMissingBaseUrl
        NetCategory.UNKNOWN -> S.errors.catUnknown
    }

    fun statusClassLabel(): String = when (statusClass) {
        StatusClass.CLIENT -> S.errors.class4xx
        StatusClass.SERVER -> S.errors.class5xx
        StatusClass.NONE -> if (isConfig) S.errors.classLocal else S.errors.badgeNoResponse
    }

    /** 单行日志，与 detail 同样脱敏 */
    fun logLine(): String =
        "[${badge()}] ${categoryLabel()} $method $endpoint" +
            (model?.let { " model=$it" } ?: "") +
            (transport?.let { " via=$it" } ?: "") +
            (providerMessage?.takeIf { it.isNotBlank() }?.let { " msg=${it.take(160)}" } ?: "")

    /**
     * 处置方式。这一函数取代了旧的布尔 `isFatalSpeechError`，它有两个实测缺陷：
     * 对所有非 HTTP 异常一律返回 false（飞行模式也要重试 8 次 ≈ 91 秒），
     * 且把 400/413/422 判成整本书致命（一段被内容审核拦下就停掉整本）。
     *
     * @param online 设备是否有可用链路；无链路时连不上就没必要重试
     */
    fun disposition(online: Boolean = true): Disposition = when (category) {
        NetCategory.CONFIG_MISSING_KEY, NetCategory.CONFIG_MISSING_BASE_URL -> Disposition.STOP_SESSION
        NetCategory.AUTH, NetCategory.QUOTA, NetCategory.NOT_FOUND, NetCategory.PRECONDITION -> Disposition.STOP_SESSION
        // 400 类：这一段不被接受（太长 / 被审核 / 音色不支持），跳过它继续读整本书
        NetCategory.BAD_REQUEST -> Disposition.SKIP_SEGMENT
        NetCategory.EMPTY_AUDIO -> if (attempt >= 2) Disposition.SKIP_SEGMENT else Disposition.RETRY
        NetCategory.PARSE -> if (attempt >= 2) Disposition.SKIP_SEGMENT else Disposition.RETRY
        NetCategory.TLS -> if (attempt >= 2) Disposition.STOP_SESSION else Disposition.RETRY
        NetCategory.CONNECTIVITY -> if (!online) Disposition.STOP_SESSION else Disposition.RETRY
        NetCategory.SERVER -> if (status == 501) Disposition.SKIP_SEGMENT else Disposition.RETRY
        NetCategory.RATE_LIMIT, NetCategory.TIMEOUT -> Disposition.RETRY
        NetCategory.UNKNOWN -> Disposition.RETRY
    }

    companion object {
        fun hostOf(endpoint: String): String = runCatching {
            java.net.URI(endpoint).host ?: ""
        }.getOrDefault("")
    }
}

/**
 * 携带 [NetError] 的异常。
 *
 * 必须保留这个字段：kotlinx.coroutines 的 StackTraceRecovery 在跨 `Deferred.await()` 时会尝试
 * 反射复制异常，但 `createConstructor` 在「异常自带额外字段」时返回 nullResult，于是原对象原样传出。
 * 一旦把负载塞回 message 字符串、让这个类变成无字段异常，它就会重新进入可复制路径。
 */
class NetException(val error: NetError) : IOException(error.logLine())

/** 从 Throwable 取出 NetError；不是 NetException 则返回 null */
fun Throwable.netError(): NetError? = (this as? NetException)?.error

object NetErrors {

    /** 512 字节上限 + 结构化脱敏 */
    fun redact(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cut = if (s.length > 512) s.take(512) + "…" else s
        return cut
            .replace(Regex("(sk-[A-Za-z0-9_\\-]{4})[A-Za-z0-9_\\-]{6,}"), "$1…")
            .replace(Regex("(?i)(\"?(?:api[_-]?key|authorization|token)\"?\\s*[:=]\\s*\"?)([^\"\\s,}]{4})[^\"\\s,}]*"), "$1$2…")
            .replace(Regex("(?i)Bearer\\s+([A-Za-z0-9_\\-]{4})[A-Za-z0-9_.\\-]*"), "Bearer $1…")
    }

    /** 去掉 query（可能带 key）后的端点 */
    fun cleanEndpoint(url: String): String = runCatching {
        val u = java.net.URI(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port${u.rawPath ?: ""}"
    }.getOrDefault(url.substringBefore('?'))

    /**
     * HTTP 响应 → NetError。只接收原语，okhttp 的 Response 由调用方拆开，
     * 这样 core 不依赖 okhttp，分类逻辑也能被纯 JVM 单测直接覆盖。
     */
    fun fromHttp(
        status: Int,
        bodyRaw: String?,
        retryAfterHeader: String? = null,
        endpoint: String = "",
        method: String = "POST",
        kind: CallKind = CallKind.SYNTHESIS,
        model: String? = null,
        attempt: Int = 1,
        maxAttempts: Int = 1,
        elapsedMs: Long = 0
    ): NetError {
        val parsed = parseProviderError(bodyRaw)
        val category = when {
            // Gemini API 对无效 Key 回的是 400 INVALID_ARGUMENT + reason=API_KEY_INVALID，不是 401（实测）。
            // 只看状态码会把它当成「这一段不被接受」逐段跳过，用户永远看不到「Key 填错了」
            status in 400..403 && googleKeyRejected(bodyRaw) -> NetCategory.AUTH
            // xAI 对无效 Key 回的也是 400（实测：{"error":"Incorrect API key provided…"}），同样不能当成「这一段不被接受」
            status == 400 && keyRejectedMessage(parsed.first) -> NetCategory.AUTH
            status == 401 -> NetCategory.AUTH
            status == 403 -> if (looksAuthy(parsed.first, parsed.second)) NetCategory.AUTH else NetCategory.BAD_REQUEST
            status == 402 -> NetCategory.QUOTA
            status == 404 -> NetCategory.NOT_FOUND
            status == 408 -> NetCategory.TIMEOUT
            // 同是 429，「每分钟太快」值得退避重试，「今天的额度用完了 / 账户没钱了」重试到明天也不会好
            status == 429 -> if (hardQuota(parsed.second, bodyRaw)) NetCategory.QUOTA else NetCategory.RATE_LIMIT
            status == 400 && precondition(parsed.second, bodyRaw) -> NetCategory.PRECONDITION
            status in 400..499 -> NetCategory.BAD_REQUEST
            status in 500..599 -> NetCategory.SERVER
            else -> NetCategory.UNKNOWN
        }
        return NetError(
            category = category,
            status = status,
            providerMessage = parsed.first,
            providerCode = parsed.second,
            endpoint = cleanEndpoint(endpoint),
            method = method,
            kind = kind,
            model = model,
            retryAfterSec = parseRetryAfter(retryAfterHeader) ?: parseRetryDelay(bodyRaw),
            bodySnippet = redact(bodyRaw),
            attempt = attempt,
            maxAttempts = maxAttempts,
            elapsedMs = elapsedMs
        )
    }

    /**
     * 异常 → NetError。**顺序至关重要**：
     * SocketTimeoutException 是 InterruptedIOException 的子类（okhttp 的 callTimeout 抛后者），
     * 另三个 SSL 异常是 SSLException 的子类；父类写在前面会把子类全部吞掉。
     */
    fun fromThrowable(
        t: Throwable,
        endpoint: String = "",
        method: String = "POST",
        kind: CallKind = CallKind.SYNTHESIS,
        model: String? = null,
        attempt: Int = 1,
        maxAttempts: Int = 1,
        elapsedMs: Long = 0
    ): NetError {
        (t as? NetException)?.let {
            return it.error.copy(attempt = attempt, maxAttempts = maxAttempts)
        }
        val category = when (t) {
            // 地址写错了（没有 http://、夹着全角字符）：这是配置问题，重试四遍也不会好
            is IllegalArgumentException -> if (t.message?.contains("URL", ignoreCase = true) == true) NetCategory.CONFIG_MISSING_BASE_URL else NetCategory.UNKNOWN
            is SocketTimeoutException -> NetCategory.TIMEOUT
            is InterruptedIOException -> NetCategory.TIMEOUT
            is UnknownHostException -> NetCategory.CONNECTIVITY
            is ConnectException, is NoRouteToHostException, is PortUnreachableException -> NetCategory.CONNECTIVITY
            is SSLPeerUnverifiedException, is SSLHandshakeException -> NetCategory.TLS
            is SSLException -> NetCategory.TLS
            is SocketException -> NetCategory.CONNECTIVITY
            is IOException -> if (t.javaClass.simpleName.contains("StreamReset")) NetCategory.SERVER else NetCategory.CONNECTIVITY
            else -> if (t.javaClass.name.startsWith("kotlinx.serialization")) NetCategory.PARSE else NetCategory.UNKNOWN
        }
        return NetError(
            category = category,
            status = null,
            providerMessage = t.message?.takeIf { it.isNotBlank() },
            endpoint = cleanEndpoint(endpoint),
            method = method,
            kind = kind,
            model = model,
            transport = t.javaClass.simpleName.ifEmpty { t.javaClass.name },
            attempt = attempt,
            maxAttempts = maxAttempts,
            elapsedMs = elapsedMs
        )
    }

    fun config(missingKey: Boolean, endpoint: String = "", kind: CallKind = CallKind.SYNTHESIS): NetError = NetError(
        category = if (missingKey) NetCategory.CONFIG_MISSING_KEY else NetCategory.CONFIG_MISSING_BASE_URL,
        endpoint = if (endpoint.isEmpty()) "" else cleanEndpoint(endpoint),
        kind = kind
    )

    /** Retry-After 支持「秒数」与 HTTP-date 两种写法，这里只取前者，后者交给退避曲线 */
    fun parseRetryAfter(h: String?): Long? =
        h?.trim()?.toLongOrNull()?.takeIf { it in 0..3600 }

    /**
     * Google RPC 的 RetryInfo：`"retryDelay": "41s"`。Gemini API 的 429 常常只把等待时长写在响应体里，
     * 不发 Retry-After 头；读出来才能「服务商要求等多久就等多久」。
     */
    fun parseRetryDelay(raw: String?): Long? =
        raw?.let { Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(it) }
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 0..3600 }

    private fun looksAuthy(msg: String?, code: String?): Boolean {
        val s = "${msg.orEmpty()} ${code.orEmpty()}".lowercase()
        return listOf("key", "auth", "token", "credential", "permission", "unauthor", "forbidden").any { it in s }
    }

    private val KEY_REJECTED = Regex(
        "(incorrect|invalid|wrong|missing|expired)[^.]{0,24}api[ _-]?key|api[ _-]?key[^.]{0,24}(incorrect|invalid|not valid|expired|not found)",
        RegexOption.IGNORE_CASE
    )

    /** 服务商的原话明说了「Key 不对」 */
    private fun keyRejectedMessage(msg: String?): Boolean = msg != null && KEY_REJECTED.containsMatchIn(msg)

    /** Google RPC ErrorInfo 的 reason 以 API_KEY_ 开头：无效 / 被限制 / 未开通该服务的 Key */
    private fun googleKeyRejected(raw: String?): Boolean =
        raw != null && Regex("\"reason\"\\s*:\\s*\"API_KEY_[A-Z_]+\"").containsMatchIn(raw)

    /**
     * 不可自愈的 429：Interactions API 的 `quota_exceeded`（当日配额耗尽）、OpenAI 的 `insufficient_quota`（没余额），
     * 以及 Google RPC 的按天配额（QuotaFailure 的 quotaId 带 PerDay）。
     */
    private fun hardQuota(code: String?, raw: String?): Boolean {
        val c = code?.lowercase()
        if (c == "quota_exceeded" || c == "insufficient_quota") return true
        return raw != null && Regex("\"quotaId\"\\s*:\\s*\"[^\"]*PerDay").containsMatchIn(raw)
    }

    /** Interactions API 的 `failed_precondition`，或 Google RPC 的 status=FAILED_PRECONDITION */
    private fun precondition(code: String?, raw: String?): Boolean =
        code.equals("failed_precondition", ignoreCase = true) ||
            (raw != null && Regex("\"status\"\\s*:\\s*\"FAILED_PRECONDITION\"").containsMatchIn(raw))

    /**
     * 宽容地取出服务商的错误描述。真实世界里至少有六种形状：
     * OpenAI/OpenRouter `{"error":{"message":…,"code":…}}`、`{"error":"…"}`、
     * `{"message":…}`、FastAPI/SiliconFlow `{"detail":…}` 或 `{"detail":[{"msg":…}]}`，
     * 以及 Google RPC `[{"error":{"code":400,"message":…,"status":…,"details":[{"reason":…}]}}]`
     * （POST 时外面还包着一层数组，实测）。旧实现只认第一种，于是另外几种一律显示空白。
     *
     * 代码优先取字符串型 `code`（Interactions API 的 `quota_exceeded`），其次是 Google 的
     * `reason`（`API_KEY_INVALID`）与 `status`（`FAILED_PRECONDITION`）—— Google 的 `code` 是数字。
     *
     * 用手写扫描而非 kotlinx.serialization：错误体经常是半截 JSON 或干脆是 HTML，
     * 解析器抛异常的概率高于成功，而我们要的只是那一句话。
     */
    fun parseProviderError(raw: String?): Pair<String?, String?> {
        val s = raw?.trim() ?: return null to null
        if (s.isEmpty()) return null to null
        if (!s.startsWith("{") && !s.startsWith("[")) {
            // 非 JSON：HTML 网关页之类，抓 <title> 或首行
            val title = Regex("(?i)<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.trim()
            val line = title ?: s.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            return line?.take(200) to null
        }
        val msg = firstStringField(s, listOf("message", "detail", "msg", "error_description")) ?: run {
            // {"error":"字符串形式"}
            Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(s)?.groupValues?.get(1)
        }
        val code = firstStringField(s, listOf("code", "reason", "status", "type"))
        return unescape(msg)?.take(400) to unescape(code)?.take(80)
    }

    private fun firstStringField(json: String, names: List<String>): String? {
        for (n in names) {
            val m = Regex("\"$n\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun unescape(s: String?): String? = s
        ?.replace("\\\"", "\"")
        ?.replace("\\n", " ")
        ?.replace("\\r", "")
        ?.replace("\\t", " ")
        ?.replace("\\/", "/")
        ?.replace("\\\\", "\\")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
