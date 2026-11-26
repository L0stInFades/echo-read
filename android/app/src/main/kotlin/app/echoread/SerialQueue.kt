package app.echoread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 串行任务队列：随时投递，按投递顺序逐个处理，不丢、不合并。
 *
 * 不用 `MutableStateFlow<List>` 充当队列：StateFlow 是「状态」而不是「队列」。读出整批再置空不是原子的，
 * 投递方一旦不在主线程，夹在两步之间新到的条目会被置空覆盖；它还按相等性合并 —— 上一批还在处理时
 * 又投来同样的一批，值与收集方上次拿到的相等，收集方根本收不到。
 *
 * [handle] 必须自己兜住异常：抛出会结束消费循环，之后投递的条目再也不会被处理。
 */
class SerialQueue<T>(scope: CoroutineScope, private val handle: suspend (T) -> Unit) {
    private val items = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch { for (item in items) handle(item) }
    }

    /** 无界通道：投递永不挂起、永不失败 */
    fun offer(batch: Iterable<T>) {
        for (item in batch) items.trySend(item)
    }
}
