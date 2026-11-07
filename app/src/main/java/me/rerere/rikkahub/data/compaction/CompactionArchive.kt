package me.rerere.rikkahub.data.compaction

import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 压缩存档。
 *
 * 每次压缩前，把**完整对话**按可读的 Markdown 存进 App 私有目录，
 * 供 AI 之后用 search_history 回查（压缩摘要会丢细节，存档保命）。
 *
 * 目录结构（和用户给的格式一致）：
 *
 *   files/compaction/<助手名>/<会话名>/压缩会话<N> <时间>.md
 *
 * N 从 1 开始递增：同一会话压第二次就是「压缩会话2 …」，
 * 所以每次压缩都留一份独立快照，不覆盖历史。
 *
 * 注意：存在 App 私有目录，不是 Linux 沙箱（用户明确要求）。
 */
object CompactionArchive {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** 文件名里不能出现的字符，统一换成下划线。 */
    private fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "untitled" }.take(80)
    }

    fun rootDir(context: Context): File = File(context.filesDir, "compaction")

    fun conversationDir(context: Context, assistantName: String, conversationTitle: String): File =
        File(File(rootDir(context), sanitize(assistantName)), sanitize(conversationTitle))

    /**
     * 存一份快照，返回落盘文件。
     *
     * 序号取目录里已有的「压缩会话N」最大值 + 1，所以删掉中间某份也不会串号。
     */
    fun save(
        context: Context,
        assistantName: String,
        conversationTitle: String,
        messages: List<UIMessage>,
        now: LocalDateTime = LocalDateTime.now(),
    ): File? {
        val dir = conversationDir(context, assistantName, conversationTitle)
        if (!dir.exists() && !dir.mkdirs()) return null

        val nextIndex = (dir.listFiles().orEmpty()
            .mapNotNull { Regex("""压缩会话(\d+)""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0) + 1

        val file = File(dir, "压缩会话$nextIndex ${now.format(STAMP)}.md")
        val body = buildMarkdown(assistantName, conversationTitle, messages, now)
        return runCatching {
            file.writeText(body, Charsets.UTF_8)
            file
        }.getOrNull()
    }

    data class Entry(
        val file: File,
        val assistantName: String,
        val conversationTitle: String,
        val index: Int,
    )

    fun list(
        context: Context,
        assistantName: String? = null,
        conversationTitle: String? = null,
    ): List<Entry> {
        val base = when {
            assistantName != null && conversationTitle != null ->
                conversationDir(context, assistantName, conversationTitle)
            assistantName != null -> File(rootDir(context), sanitize(assistantName))
            else -> rootDir(context)
        }
        if (!base.exists()) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile && it.name.startsWith("压缩会话") && it.name.endsWith(".md") }
            .mapNotNull { file ->
                val index = Regex("""压缩会话(\d+)""").find(file.name)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                Entry(
                    file = file,
                    assistantName = file.parentFile?.parentFile?.name.orEmpty(),
                    conversationTitle = file.parentFile?.name.orEmpty(),
                    index = index,
                )
            }
            .sortedWith(compareBy({ it.assistantName }, { it.conversationTitle }, { it.index }))
            .toList()
    }

    data class Hit(
        val entry: Entry,
        val line: String,
    )

    /** 关键词检索，返回命中的行（每份最多 5 条，总共不超过 limit）。 */
    fun search(
        context: Context,
        keyword: String,
        limit: Int = 20,
        assistantName: String? = null,
        conversationTitle: String? = null,
    ): List<Hit> {
        if (keyword.isBlank()) return emptyList()
        val hits = mutableListOf<Hit>()
        for (entry in list(context, assistantName, conversationTitle)) {
            if (hits.size >= limit) break
            val text = runCatching { entry.file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            var perFile = 0
            for (line in text.lineSequence()) {
                if (perFile >= 5 || hits.size >= limit) break
                if (line.contains(keyword, ignoreCase = true) && line.isNotBlank()) {
                    hits += Hit(entry, line.trim().take(300))
                    perFile++
                }
            }
        }
        return hits
    }

    /** 删除某个会话的全部存档。 */
    fun deleteConversation(context: Context, assistantName: String, conversationTitle: String) {
        runCatching { conversationDir(context, assistantName, conversationTitle).deleteRecursively() }
    }

    /** 删除某个助手的全部存档。 */
    fun deleteAssistant(context: Context, assistantName: String) {
        runCatching { File(rootDir(context), sanitize(assistantName)).deleteRecursively() }
    }

    // ---- Markdown 生成 ----------------------------------------------------

    /**
     * 把对话渲染成存档用 Markdown。
     *
     * 和「分享导出」的区别：图片、附件只留 `[图片]` / `[文件]` 两个字。
     * 存档是给 AI 检索的，base64 只会占体积、还会把它带偏 ——
     * 用户要求「主要看思考和回答」。
     */
    fun buildMarkdown(
        assistantName: String,
        conversationTitle: String,
        messages: List<UIMessage>,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        appendLine("# $conversationTitle")
        appendLine()
        appendLine("> 助手：$assistantName ｜ 存档时间：${now.format(STAMP)} ｜ 消息数：${messages.size}")
        appendLine()
        appendLine("---")
        appendLine()

        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                MessageRole.SYSTEM -> "系统"
                MessageRole.TOOL -> "工具"
            }
            appendLine("## $role")
            appendLine()
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotBlank()) {
                            appendLine(part.text)
                            appendLine()
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        part.reasoning.lines().filter { it.isNotBlank() }.forEach {
                            appendLine("> $it")
                        }
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        appendLine("**[工具] ${part.toolName}**")
                        part.output.filterIsInstance<UIMessagePart.Text>().forEach {
                            appendLine("```")
                            appendLine(it.text.take(2000))
                            appendLine("```")
                        }
                        appendLine()
                    }

                    // 只留占位，不带任何提示词
                    is UIMessagePart.Image -> {
                        appendLine("[图片]")
                        appendLine()
                    }

                    is UIMessagePart.Video,
                    is UIMessagePart.Audio,
                    is UIMessagePart.Document -> {
                        appendLine("[文件]")
                        appendLine()
                    }

                    else -> Unit
                }
            }
            appendLine("---")
            appendLine()
        }
    }
}
