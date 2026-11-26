package app.echoread.data

import app.echoread.core.net.CallKind
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 HTTP 客户端：朗读请求（tts）与应用内更新（本层的 [Updater]）走同一套超时预算与代理规则，
 * 派生出来的几个客户端共用同一个连接池与线程池。放在 data 而不在 tts：更新器在这一层，不能反过来依赖 tts。
 */
object HttpClients {
    /**
     * 基础客户端。**没有 callTimeout** —— 仅供 APK 下载这种长任务使用。
     * 其余调用一律走 [clientFor]，否则单次请求最坏可挂到 connect 20s + read 90s，
     * 乘上重试次数就是十几分钟看不到任何错误。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(LoopbackDirect)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 是不是本机地址（localhost、127.x.x.x、::1） */
    fun isLoopback(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        return h == "localhost" || h.endsWith(".localhost") || h == "::1" || Regex("^127(\\.\\d{1,3}){3}$").matches(h)
    }

    /**
     * 本机地址一律直连，不交给系统代理。
     * Wi-Fi 上配了 HTTP 代理而没填排除列表时，系统会把 http://127.0.0.1:8880 也交给代理 ——
     * 代理那头的 127.0.0.1 是它自己，于是跑在手机上的朗读服务只会得到一个 503（真机实测）。
     * 浏览器对 localhost 也是这么处理的。其余地址仍按系统设置走。
     */
    internal object LoopbackDirect : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): List<java.net.Proxy> {
            if (uri?.host?.let { isLoopback(it) } == true) return listOf(java.net.Proxy.NO_PROXY)
            val system = runCatching { getDefault()?.takeIf { it !== this }?.select(uri) }.getOrNull()
            return system?.takeIf { it.isNotEmpty() } ?: listOf(java.net.Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            runCatching { getDefault()?.takeIf { it !== this }?.connectFailed(uri, sa, ioe) }
        }
    }

    /** 合成：整次调用 75s 封顶（含连接、写、读、重定向），保证退避预算可预测 */
    private val synthClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(75, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** 元数据（模型列表 / 余额 / 更新清单）：25s 封顶，用户正盯着转圈 */
    private val metaClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(25, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    fun clientFor(kind: CallKind): OkHttpClient = when (kind) {
        CallKind.SYNTHESIS -> synthClient
        CallKind.UPDATE_APK -> client
        else -> metaClient
    }
}
