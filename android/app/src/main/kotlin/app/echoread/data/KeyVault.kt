package app.echoread.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.echoread.core.GeminiTtsConfig
import app.echoread.core.OpenAISpeechConfig
import app.echoread.core.TtsSettings
import app.echoread.tts.Providers
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的落盘保护（0.5.0）。
 *
 * 0.4.x 把 Key 明文写在 SharedPreferences 里，而清单开着 `allowBackup`：
 * 换机迁移、云备份都会把这份明文一起带走。自带 Key 的应用里，Key 是唯一真正值钱的东西。
 *
 * 现在：内存里是明文，落盘前用 Android Keystore 里的一把 AES-256-GCM 密钥加密。
 * 这把密钥由系统保管、不出本机，所以备份出去的只是一串在别的设备上解不开的密文 ——
 * 恢复到新手机后 Key 那一栏是空的，重新填一次即可，其余设置都在。
 */
interface Vault {
    /** 明文 → 落盘形态。空串原样返回；加密失败时返回明文（宁可退回旧行为，也不能把用户的 Key 弄丢） */
    fun seal(plain: String): String

    /** 落盘形态 → 明文。没有密文前缀的按明文处理（0.4.x 的存档）；解不开的返回空串 */
    fun open(stored: String): String
}

class KeystoreVault : Vault {
    /** 明文 → 密文。设置每改一次都会整份落盘（拖一下语速滑块就是几十次），没变的 Key 不重复加密 */
    private val sealed = HashMap<String, String>()
    private val opened = HashMap<String, String>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    override fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        sealed[plain]?.let { return it }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = PREFIX + Base64.getEncoder().encodeToString(cipher.iv + body)
            sealed[plain] = out
            opened[out] = plain
            out
        } catch (_: Throwable) {
            plain
        }
    }

    override fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        opened[stored]?.let { return it }
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES)) }
            val plain = String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
            opened[stored] = plain
            sealed[plain] = stored
            plain
        } catch (_: Throwable) {
            // 密钥不在了（恢复到另一台设备、清除了凭据存储）：这把 Key 已经读不回来，交给用户重填
            ""
        }
    }

    companion object {
        const val PREFIX = "enc1:"
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "lector.keys.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/** 落盘前后的整份转换（纯函数，单测用一个假的 [Vault] 直接验证） */
object Sealing {
    /**
     * 落盘形态：各服务的 Key 与标了 secret 的字段换成密文；0.4.x 的两个旧字段里不再留 Key。
     * 旧字段的其余部分（地址、模型、音色）照常回写，降级安装时配置还在，只需重填一次 Key。
     */
    fun sealed(s: TtsSettings, v: Vault): TtsSettings = s.copy(
        profiles = s.profiles.map { p ->
            val secret = Providers.specOf(p, s).fields.filter { it.secret }.map { it.id }.toSet()
            p.copy(apiKey = v.seal(p.apiKey), fields = p.fields.mapValues { (k, value) -> if (k in secret) v.seal(value) else value })
        },
        openai = s.openai.copy(apiKey = ""),
        gemini = s.gemini.copy(apiKey = "")
    )

    /** 读档：密文还原成明文。还没迁移过的 0.4.x 存档（没有 profiles）原样返回，Key 仍在旧字段里等迁移 */
    fun opened(s: TtsSettings, v: Vault): TtsSettings = s.copy(
        profiles = s.profiles.map { p ->
            p.copy(apiKey = v.open(p.apiKey), fields = p.fields.mapValues { (_, value) -> v.open(value) })
        },
        openai = openLegacy(s.openai, v),
        gemini = openLegacy(s.gemini, v)
    )

    private fun openLegacy(c: OpenAISpeechConfig, v: Vault) = c.copy(apiKey = v.open(c.apiKey))
    private fun openLegacy(c: GeminiTtsConfig, v: Vault) = c.copy(apiKey = v.open(c.apiKey))
}
