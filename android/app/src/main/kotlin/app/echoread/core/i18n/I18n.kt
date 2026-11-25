package app.echoread.core.i18n

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/** 界面语言：跟随系统 / 中文 / English（0.4.0 起） */
@Serializable
enum class AppLanguage {
    @SerialName("system") SYSTEM,
    @SerialName("zh") ZH,
    @SerialName("en") EN
}

/**
 * 当前界面语言。文案本身在语言包里（[Strings] / [ZhStrings] / [EnStrings]），代码通过 [S] 取用。
 *
 * 为什么不用 Android 的 strings.xml：错误文案（NetError）、重试提示、音色说明这些在 core / tts 层生成，
 * core 是纯 JVM、要能直接单测，拿不到 Context；类型化的语言包还让「漏翻」成为编译错误。
 *
 * 语言在启动时定下（[apply]）。用户切换语言时 Activity 重建，整棵界面树按新语言重画 ——
 * 与系统改语言时 Android 自己的做法一致，rememberSaveable 的状态（打开的书、阅读位置）都保留。
 * 正因为进程不重启，对象里「初始化一次」的文案表不能写成普通 val，见 [PerLanguage]。
 */
object I18n {
    @Volatile
    var english: Boolean = false
        private set

    /**
     * 跟随系统：系统语言是中文（简繁都算）就用中文，其余一律英文 ——
     * 对日语、德语用户来说，英文界面比中文界面更可能看得懂。
     */
    fun resolve(lang: AppLanguage, system: Locale = Locale.getDefault()): Boolean = when (lang) {
        AppLanguage.EN -> true
        AppLanguage.ZH -> false
        AppLanguage.SYSTEM -> !system.language.equals("zh", ignoreCase = true)
    }

    fun apply(lang: AppLanguage, system: Locale = Locale.getDefault()) {
        english = resolve(lang, system)
    }

    /** 数字、日期格式化用的区域 */
    val locale: Locale get() = if (english) Locale.US else Locale.CHINA

    /** 当前语言包 */
    val strings: Strings get() = if (english) EnStrings else ZhStrings

    /** 指定语言的语言包（构建按语言缓存的表时用，不读全局状态） */
    fun pack(english: Boolean): Strings = if (english) EnStrings else ZhStrings
}

/** 当前语言的文案：`S.shelf.importBooks`、`S.errors.apiKeyRejected(badge)` */
val S: Strings get() = I18n.strings

/**
 * 按语言各构建一次、各缓存一份的文案表（模型说明、音色备注这类大表）。
 * 不能写成普通的 `val`：对象只初始化一次，切换语言后表格会停在首次访问时的语言；
 * 也不想写成每次访问都重建的 getter —— 合成每一段都要查一次模型说明。
 * 构建函数拿到的是明确的语言参数，不读全局状态，所以两份缓存各自确定、不会串语言。
 */
class PerLanguage<T>(private val build: (english: Boolean) -> T) {
    private val zh by lazy { build(false) }
    private val en by lazy { build(true) }
    val value: T get() = if (I18n.english) en else zh
}
