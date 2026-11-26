package app.echoread.ui.motion

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 跟手动画的「重新瞄准」：手指每来一个样本，就让 [action] 带着最新的目标再跑一次。
 *
 * 以前是每个指针样本各起一个协程去跑，拖动时一秒几十上百个。也不能换成一个协程「先停掉上一次、再重跑」：
 * Animatable 被打断的那一刻会把速度和上一帧的时间清零，新的 animateTo 只有在旧动画还活着时调用，
 * 才接得上它的速度与时间 —— 每帧都重新瞄准的弹簧若每次都从零起步，跟手的形变就几乎出不来了。
 * 所以这里是两个常驻协程轮流接手：一个在跑，另一个等着下一个样本，接手的那一刻旧的那个还活着；
 * 被接手（或被别处的动画抢占）的那个吞掉打断，回去等下一个样本。
 *
 * 样本只留最新的一个：一帧里来了两个，只跑后一个，与逐个跑完的结果相同。已经叫醒、还没开跑的那个协程
 * 开跑时读到的就是最新的样本，所以不再多叫一次 —— 多叫的那一次会等旧动画收尾（速度、时间都已清零）之后
 * 再重启一遍，接续就断了（RetargetTest 钉着这一条）。
 */
internal class Retarget(private val action: suspend (Float) -> Unit) {
    private var latest = 0f
    private var pending = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** 交一个新样本：指针回调里直接调，不挂起、不起协程 */
    fun offer(value: Float) {
        latest = value
        if (pending) return
        pending = true
        wake.trySend(Unit)
    }

    /** 常驻在调用方给的协程里，随它一起取消 */
    suspend fun run() {
        coroutineScope {
            repeat(2) {
                launch {
                    while (true) {
                        wake.receive()
                        pending = false
                        preemptable { action(latest) }
                    }
                }
            }
        }
    }
}
