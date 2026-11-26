package app.echoread.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import app.echoread.AppGraph
import app.echoread.core.Providers
import app.echoread.core.i18n.S
import app.echoread.ui.EchoTextField
import app.echoread.ui.GradientButton
import app.echoread.ui.SoftButton
import app.echoread.ui.Toaster
import app.echoread.ui.echo

/* ---------------- 粘贴服务配置 ---------------- */

@Composable
internal fun ImportPage(graph: AppGraph, onAdded: (String) -> Unit) {
    val c = echo
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    PageColumn {
        Text(S.providers.importHint, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        EchoTextField(
            text, { text = it; error = "" }, placeholder = "{ \"id\": \"my-tts\", \"name\": \"My TTS\", \"kind\": \"http\", ... }",
            singleLine = false, mono = true, minLines = 9
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(S.providers.paste, Modifier.weight(1f)) {
                clipboard.getText()?.text?.let { text = it; error = "" }
            }
            GradientButton(S.providers.add, Modifier.weight(1f), enabled = text.isNotBlank()) {
                Providers.parseSpec(text).fold(
                    onSuccess = { spec ->
                        // 导入的定义不能顶替内置服务商：否则一段粘贴来的 JSON 就能把已存的 Key 引到别的主机上
                        if (Providers.builtIn.any { it.id == spec.id }) error = S.providers.importFailed("id \"${spec.id}\" is built in")
                        else {
                            val p = graph.settings.importSpec(spec)
                            Toaster.success(S.providers.serviceAdded)
                            onAdded(p.id)
                        }
                    },
                    onFailure = { error = S.providers.importFailed(it.message ?: "?") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
