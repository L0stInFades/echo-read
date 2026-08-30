package app.echoread.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun flipPages() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        // 不设 startupMode：设了会在 setupBlock 之后再杀进程，measureBlock 里就没有阅读页了
        startupMode = null,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            ensureSampleBook()
            // 上一轮结束时仍停在阅读页：直接翻，不再点书架
            if (device.findObject(device.tag("reader.page")) == null) openFirstBook()
        },
    ) {
        flipPages(8)
    }
}
