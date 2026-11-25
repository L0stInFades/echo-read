package app.echoread.tts

import app.echoread.core.Discovery
import app.echoread.core.Template
import app.echoread.core.net.CallKind
import app.echoread.core.net.NetError
import app.echoread.core.net.NetErrors
import app.echoread.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一个服务的连接状态（界面上的那个圆点与那行字） */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus

    data class Ok(
        /** 有一次带鉴权的请求成功了。false = 这家没有可用来验证的列表接口，只能靠试听 */
        val verified: Boolean,
        val models: Int = 0,
        val voices: Int = 0,
        /** 其中属于用户自己的音色 */
        val own: Int = 0,
        val credits: SpeechApi.Credits? = null,
        /** 余额单独失败：连接本身仍算成功，但必须显示出来 —— /credits 的 401 常常是 Key 被吊销的最早证据 */
        val creditsError: NetError? = null,
        /** 带鉴权的请求被拒、匿名的却成功：配置很可能是坏的，只是这次被网关放行了 */
        val suspiciousAuth: NetError? = null
    ) : SyncStatus {
        /** 有任何可疑信号就不给绿灯：一个建立在未验证字节上的「已连接」比没有指示器更糟 */
        val warning: NetError? get() = suspiciousAuth ?: creditsError
    }

    data class Failed(val error: NetError) : SyncStatus
}

/**
 * 各服务的同步（拉模型表 / 音色表，顺带验证 Key）与连接状态。
 * 放在应用级：书架与阅读器里打开的是同一张设置面板，状态不该各算各的；面板关掉，在途的同步也不取消。
 */
class ServiceSync(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<String, SyncStatus>> = _status
    private val jobs = HashMap<String, Job>()

    /** 每个服务当前这条状态是拿哪一把「端点 + Key」算出来的（见 [needsSync]） */
    private val checkedFp = HashMap<String, String>()

    private fun set(id: String, st: SyncStatus) = _status.update { it + (id to st) }

    fun forget(id: String) {
        jobs.remove(id)?.cancel()
        checkedFp.remove(id)
        _status.update { it - id }
    }

    /** 立即同步一次。[onDone] 在主线程回调（手动「刷新」时用来弹提示） */
    fun refresh(profileId: String, onDone: ((SyncStatus) -> Unit)? = null) {
        jobs.remove(profileId)?.cancel()
        val p = settings.profile(profileId) ?: return
        val s = settings.tts.value
        val spec = Providers.specOf(p, s)
        if (!Providers.ready(spec, p)) {
            checkedFp.remove(profileId)
            set(profileId, SyncStatus.Idle)
            onDone?.invoke(SyncStatus.Idle)
            return
        }
        checkedFp[profileId] = Providers.fingerprint(spec, p)
        set(profileId, SyncStatus.Syncing)
        jobs[profileId] = scope.launch {
            val st = try {
                val r = Providers.sync(p, s)
                // 入口快照：同步期间用户可能又改了 Key，指纹记的是发请求时的那一把
                settings.setDiscovery(
                    profileId,
                    Discovery(models = r.models, voices = r.voices, at = System.currentTimeMillis(), fp = Providers.fingerprint(spec, p), model = Providers.modelOf(spec, p))
                )
                SyncStatus.Ok(
                    verified = r.verified,
                    models = r.models.size,
                    voices = Providers.voiceCatalog(spec, p, settings.discovery.value[profileId]).size,
                    own = r.voices.count { it.mine },
                    credits = r.credits,
                    creditsError = r.creditsError,
                    suspiciousAuth = r.suspiciousAuth
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SyncStatus.Failed(
                    NetErrors.fromThrowable(e, endpoint = Providers.baseOf(spec, p), method = "GET", kind = CallKind.MODEL_LIST, model = Providers.modelOf(spec, p).ifEmpty { null })
                )
            }
            set(profileId, st)
            onDone?.invoke(st)
        }
    }

    /**
     * 需要时才同步：本次启动还没同步过、「端点 + Key」变了、上一次失败了、缓存超过 6 小时，
     * 或者音色表按模型分而模型换了。否则什么都不发。
     */
    fun ensureFresh(profileId: String) {
        val p = settings.profile(profileId) ?: return
        val s = settings.tts.value
        val spec = Providers.specOf(p, s)
        if (!Providers.ready(spec, p)) {
            jobs.remove(profileId)?.cancel()
            checkedFp.remove(profileId)
            set(profileId, SyncStatus.Idle)
            return
        }
        val modelScoped = spec.listVoices.any { "model" in Template.names(it.url) }
        val needed = needsSync(
            cur = _status.value[profileId], checkedFp = checkedFp[profileId], fp = Providers.fingerprint(spec, p),
            d = settings.discovery.value[profileId], now = System.currentTimeMillis(), modelScoped = modelScoped, model = Providers.modelOf(spec, p)
        )
        if (needed) refresh(profileId)
    }

    companion object {
        const val MAX_AGE_MS = 6 * 3600_000L

        /**
         * 眼下这条状态还作不作数（纯函数，便于测试）。
         *
         * 状态是拿某一把「端点 + Key」算出来的，换了一把它就不作数 —— 不管它是绿是红。
         * 只比 [Discovery] 里存的指纹不够：失败不写 Discovery，于是「Key 改错（被拒绝）又改回来」之后，
         * 存着的指纹与现在的对得上，界面上却一直挂着那条旧的「API Key 被拒绝」（真机实测）。
         * 失败的状态也不留到下一次：断网、超时都是一时的，再打开面板就该重新确认。
         */
        fun needsSync(cur: SyncStatus?, checkedFp: String?, fp: String, d: Discovery?, now: Long, modelScoped: Boolean, model: String): Boolean = when {
            cur == null || cur is SyncStatus.Idle -> true
            checkedFp != fp -> true
            cur is SyncStatus.Syncing -> false
            cur is SyncStatus.Failed -> true
            else -> d == null || d.fp != fp || now - d.at > MAX_AGE_MS || (modelScoped && d.model != model)
        }
    }
}
