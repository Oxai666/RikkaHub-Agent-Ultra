package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * 高权限 shell 的执行卡片：root_shell（su）和 shizuku_exec（shell UID）。
 *
 * 为什么要单独写一个，不共用 [ShellToolUI]：
 *
 * **命令必须完整显示**。这是 su 权限，用户在点「允许」之前得看清楚
 * 到底要跑什么。通用卡片的路径是把 `tool.input` 当 JSON 原样渲染，
 * 引号和 `\n` 会被转义成字面量，长命令还容易读岔；`ShellToolUI` 那边
 * 的 title 又只取前 40 个字符。
 *
 * 这里的规矩：
 *  - title 给一行摘要（还是截断，因为要点得进折叠条）
 *  - **Summary 给完整命令，一行不删**
 *  - Preview 也是完整命令 + 输出，可以滚动
 *
 * 命令不做语法高亮以外的任何加工——不脱敏、不省略、不折叠。
 * 用户看到什么，就跑什么。
 *
 * 写成带构造参数的 class 而不是 object，是因为它要覆盖两个工具名，
 * 而 [ToolUIRegistry] 用 `associateBy { it.toolName }` 建表——
 * 一个实例只能占一个位置，所以注册两次。
 *
 * @param toolName 这个实例负责的工具名。也用作卡片标题前缀。
 */
class PrivilegedShellToolUI(override val toolName: String) : ToolUIRenderer {

    /** 折叠条上那行摘要最多这么长，超出截断加省略号。 */
    private val titleMaxChars: Int = 48

    /** 权限来源标签，显示在 Preview 右上角。 */
    private val privilegeLabel: String =
        if (toolName == "root_shell") "su" else "shizuku"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Shield01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command").orEmpty()
        val oneLine = command.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return toolName
        val truncated =
            if (oneLine.length > titleMaxChars) oneLine.take(titleMaxChars) + "…" else oneLine
        return "$toolName · $truncated"
    }

    /** 折叠时也展示——命令是这条调用最重要的信息，藏起来等于不给看。 */
    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val command = context.arguments.getStringContent("command").orEmpty()
        if (command.isBlank()) return
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CommandBox(command = command, loading = context.loading)
            // 已经执行完的把 stdout / stderr 也带上，方便对照
            val content = context.content
            if (content != null) {
                val stdout = content.getStringContent("stdout").orEmpty().trim()
                val stderr = content.getStringContent("stderr").orEmpty().trim()
                if (stdout.isNotEmpty()) {
                    OutputBox(label = "stdout", text = stdout, isError = false)
                }
                if (stderr.isNotEmpty()) {
                    OutputBox(label = "stderr", text = stderr, isError = true)
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val command = context.arguments.getStringContent("command").orEmpty()
        val content = context.content
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = privilegeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 完整命令，语法高亮，可横向滚动
            HighlightCodeBlock(
                code = command,
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (content != null) {
                val stdout = content.getStringContent("stdout").orEmpty()
                val stderr = content.getStringContent("stderr").orEmpty()
                if (stdout.isNotEmpty()) {
                    Text("stdout", style = MaterialTheme.typography.labelMedium)
                    HighlightCodeBlock(
                        code = stdout,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (stderr.isNotEmpty()) {
                    Text(
                        text = "stderr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    HighlightCodeBlock(
                        code = stderr,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    /**
     * 命令框。
     *
     * **不做任何截断**：行数不限、字符不限。用户批准的是这一段文本，
     * 少一个字就可能少看一个 `rm -rf`。
     */
    @Composable
    private fun CommandBox(command: String, loading: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    @Composable
    private fun OutputBox(label: String, text: String, isError: Boolean) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
