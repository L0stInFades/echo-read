package app.echoread.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.graphics.Color
import app.echoread.ui.motion.EchoMotion

/**
 * 旧动效入口，仅作兼容转发，新代码一律用 `app.echoread.ui.motion` 下的 token 与驱动器：
 * - 弹簧档位 `EchoMotion.Instant/Track/Standard/Emphasized/Gentle/Playful`（response + damping 参数化）
 * - 手势驱动 `MotionDriver` + `driveHorizontally/driveVertically`
 * - 按压反馈 `Modifier.echoPress`
 */
@Deprecated("改用 app.echoread.ui.motion.EchoMotion 的 (response, damping) 档位")
object Motion {
    val spring: SpringSpec<Float> get() = EchoMotion.Standard.float()
    val soft: SpringSpec<Float> get() = EchoMotion.Gentle.float()
    val bouncy: SpringSpec<Float> get() = EchoMotion.Playful.float()
    val colorSpring: SpringSpec<Color> get() = EchoMotion.Standard.spec(null)
}
