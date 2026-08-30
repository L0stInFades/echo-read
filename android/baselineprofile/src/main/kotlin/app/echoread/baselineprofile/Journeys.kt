package app.echoread.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "app.echoread"

private const val SHORT_TIMEOUT = 3_000L
private const val LONG_TIMEOUT = 10_000L

/** app 开启了 testTagsAsResourceId，resource id 即 testTag 字符串本身。 */
fun UiDevice.tag(t: String): BySelector = By.res(t)

private fun UiDevice.waitFor(t: String, timeout: Long = LONG_TIMEOUT): UiObject2? =
    wait(Until.findObject(tag(t)), timeout)

/** 书架为空时点「没有书？先听示例 →」导入示例书，直到出现书籍单元格。 */
fun MacrobenchmarkScope.ensureSampleBook() {
    if (device.waitFor("shelf.book", SHORT_TIMEOUT) != null) return
    val sample = device.waitFor("shelf.sample", SHORT_TIMEOUT)
        ?: device.wait(Until.findObject(By.textContains("示例")), SHORT_TIMEOUT)
    sample?.click()
    device.waitFor("shelf.book", LONG_TIMEOUT)
    device.waitForIdle()
}

/** 打开书架上的第一本书并等待阅读页出现。 */
fun MacrobenchmarkScope.openFirstBook() {
    val book = device.waitFor("shelf.book", LONG_TIMEOUT)
        ?: device.wait(Until.findObject(By.text("深夜书屋（示例）")), SHORT_TIMEOUT)
        ?: error("shelf.book not found")
    book.click()
    device.waitFor("reader.page", LONG_TIMEOUT) ?: error("reader.page not found")
    device.waitForIdle()
}

/** 横滑翻页 n 次，再点右侧边缘翻页 n 次。 */
fun MacrobenchmarkScope.flipPages(n: Int) {
    val page = device.waitFor("reader.page", LONG_TIMEOUT) ?: error("reader.page not found")
    val b = page.visibleBounds
    val y = b.centerY()
    repeat(n) {
        device.swipe(b.right - b.width() / 10, y, b.left + b.width() / 10, y, 10)
        device.waitForIdle()
    }
    repeat(n) {
        // 右侧 25% 区域点击翻页
        device.click(b.left + (b.width() * 0.9f).toInt(), y)
        device.waitForIdle()
    }
}

/** 可选：打开/关闭底部面板；相关 tag 不存在时直接跳过。 */
fun MacrobenchmarkScope.openAndCloseSheets() {
    listOf("reader.settings", "reader.toc").forEach { t ->
        val btn = device.findObject(device.tag(t)) ?: return@forEach
        btn.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

/** 通过 reader.back 返回书架，找不到则系统返回键。 */
fun MacrobenchmarkScope.goBack() {
    val back = device.findObject(device.tag("reader.back"))
    if (back != null) back.click() else device.pressBack()
    device.waitFor("shelf.book", LONG_TIMEOUT)
    device.waitForIdle()
}

@Suppress("unused")
private fun UiObject2.scrollLeft() = fling(Direction.LEFT)
