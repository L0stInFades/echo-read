package app.echoread.ui.voice

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.echoread.AppGraph
import app.echoread.core.Providers
import app.echoread.core.i18n.S
import app.echoread.ui.EchoSheet
import app.echoread.ui.SheetPages
import kotlinx.coroutines.delay

/*
 * 朗读设置（0.5.0）：一张面板、几页内容。
 *
 * 0.4.x 的结构是「引擎三选一（AI 语音 / Gemini / 系统语音）+ 各自一套设置界面」，
 * 每接一家新的服务商就要多一个分段、多一个设置文件、多两张选择器面板。现在：
 *
 *   主页        当前服务（一张卡片）· 模型 · 音色 · 语气 · 语速 · 高级
 *   └ 朗读服务   已添加的服务，点一下切换；每个服务各存各的 Key
 *      └ 添加服务  内置目录（按地区分组）+ 自定义端点 + 粘贴服务配置
 *      └ 服务设置  Key、地址、额外字段、连接检查、移除
 *   └ 模型 / 音色  同一张面板里的一页，不再另开一张面板叠在上面
 *
 * 界面完全由目录数据驱动：这里没有任何一家服务商的专属代码，新接入的服务商自动获得这一整套界面。
 * 每页一个文件（MainPage、ServicesPage、AddServicePage、ServicePage、ImportPage、ModelsPage、VoicesPage），
 * 各页共用的名字、头像、状态行在 VoiceParts。
 */

internal sealed interface VoicePage {
    val depth: Int

    data object Main : VoicePage { override val depth = 0 }
    data object Models : VoicePage { override val depth = 1 }
    data object Voices : VoicePage { override val depth = 1 }
    data object Services : VoicePage { override val depth = 1 }
    data object Add : VoicePage { override val depth = 2 }
    data class Service(val id: String) : VoicePage { override val depth = 3 }
    data object Import : VoicePage { override val depth = 3 }
}

/* ---------------- 面板 ---------------- */

@Composable
fun BoxScope.TtsSettingsSheet(open: Boolean, graph: AppGraph, onClose: () -> Unit) {
    val settings = graph.settings
    val tts by settings.tts.collectAsState()
    val discovery by settings.discovery.collectAsState()
    val statuses by graph.services.status.collectAsState()

    var stack by remember { mutableStateOf(listOf<VoicePage>(VoicePage.Main)) }
    LaunchedEffect(open) {
        // 每次打开都从主页开始：缺 Key 时主页顶上的服务卡片就是「填入 Key」的按钮，不必替人跳进服务页
        if (open) stack = listOf(VoicePage.Main)
    }
    val page = stack.last()
    fun push(p: VoicePage) { stack = stack + p }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    val active = tts.activeProfile
    // 面板打开时（以及换了服务 / Key / 地址之后稍等片刻）确认一次连接：去抖 800ms，输入 Key 的过程中不逐字发请求
    LaunchedEffect(open, active?.id, active?.apiKey, active?.baseUrl, active?.fields, active?.model) {
        val id = active?.id ?: return@LaunchedEffect
        if (!open) return@LaunchedEffect
        delay(800)
        graph.services.ensureFresh(id)
    }

    val title = when (page) {
        VoicePage.Main -> S.common.voiceSettings
        VoicePage.Models -> S.common.model
        VoicePage.Voices -> S.common.voice
        VoicePage.Services -> S.providers.servicesTitle
        VoicePage.Add -> S.providers.addService
        is VoicePage.Service -> settings.profile(page.id)?.let { serviceName(Providers.specOf(it, tts), it) } ?: S.providers.service
        VoicePage.Import -> S.providers.pasteDefinition
    }

    EchoSheet(
        open = open, onDismiss = onClose, title = title,
        maxHeightFraction = 0.9f, scrollable = false, fillHeight = true,
        onBack = if (stack.size > 1) ({ pop() }) else null
    ) {
        SheetPages(page, depthOf = { it.depth }, modifier = Modifier.fillMaxSize()) { p ->
            when (p) {
                VoicePage.Main -> MainPage(graph, tts, discovery, statuses, onOpen = { push(it) })
                VoicePage.Services -> ServicesPage(graph, tts, discovery, statuses, onOpen = { push(it) })
                VoicePage.Add -> AddServicePage(
                    graph, tts,
                    onAdded = { id -> stack = stack.dropLast(1) + VoicePage.Service(id) },
                    onImport = { push(VoicePage.Import) }
                )
                is VoicePage.Service -> ServicePage(graph, tts, p.id, statuses[p.id], onRemoved = { pop() }, onUse = { stack = listOf(VoicePage.Main) })
                VoicePage.Import -> ImportPage(graph, onAdded = { id -> stack = stack.filterNot { it is VoicePage.Add || it is VoicePage.Import } + VoicePage.Service(id) })
                VoicePage.Models -> active?.let { ModelsPage(graph, tts, it, discovery[it.id], onPicked = { pop() }) }
                VoicePage.Voices -> active?.let { VoicesPage(graph, tts, it, discovery[it.id], statuses[it.id], onPicked = { pop() }) }
            }
        }
    }
}

/** 一页的滚动容器：各页自己滚，面板本身不滚（否则换页时滚动位置会串） */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    // 每次进入这一页都从顶部开始（不保存滚动位置）
    Column(Modifier.fillMaxSize().verticalScroll(remember { androidx.compose.foundation.ScrollState(0) }), content = content)
}
