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

    /** 兜底用的 Application context：Repository 层拿不到 Context 时靠它。 */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(context: Context?): Context =
        context ?: appContext ?: error("CompactionArchive 未初始化（App 启动时要调 init）")

    /** 文件名里不能出现的字符，统一换成下划线。 */
    private fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "untitled" }.take(80)
    }

    /** 目录名末尾的 "__<id8>"，剥掉还原成人能看懂的名字。 */
    private val ID_SUFFIX = Regex("""__[0-9a-zA-Z]{8}$""")

    private fun stripIdSuffix(dirName: String): String =
        dirName.replace(ID_SUFFIX, "").ifBlank { dirName }

    fun rootDir(context: Context? = null): File = File(ctx(context).filesDir, "compaction")

    /**
     * 助手目录 / 会话目录都带上 id 后缀。
     *
     * 只用名字会出两个问题：
     *  - 两个会话同名（比如都叫「新对话」）→ 存档混在一起
     *  - 会话 A 的模型能翻到会话 B 的历史
     * 带上前 8 位 id 之后目录既好认又唯一。
     */
    fun assistantDir(
        context: Context?,
        assistantName: String,
        assistantId: String,
    ): File = File(rootDir(context), "${sanitize(assistantName)}__${assistantId.take(8)}")

    fun conversationDir(
        context: Context?,
        assistantName: String,
        assistantId: String,
        conversationTitle: String,
        conversationId: String,
    ): File = File(
        assistantDir(context, assistantName, assistantId),
        "${sanitize(conversationTitle)}__${conversationId.take(8)}",
    )

    /** 记录「上一份存档存到哪条消息」，用来算增量。 */
    private const val STATE_FILE = "_state.txt"

    private fun readLastStamp(dir: File): String? {
        val file = File(dir, STATE_FILE)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8).trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun writeLastStamp(dir: File, stamp: String) {
        runCatching { File(dir, STATE_FILE).writeText(stamp, Charsets.UTF_8) }
    }

    /**
     * 存一份快照，返回落盘文件。**只存增量**。
     *
     * 用户定的规则：
     *   消息 1、2 → 压缩① → 压缩会话1 记录 1、2
     *   输入 3    → 压缩② → 压缩会话2 只记录 3（1、2 已经在上一份里了）
     *
     * 实现上靠目录里的 _state.txt 记住上一份存到哪条消息的 createdAt，
     * 下次只取这之后的消息。同一份内容重复触发压缩时，增量可能为空，
     * 这时直接返回 null，不产生空文件。
     */
    fun save(
        context: Context?,
        assistantName: String,
        assistantId: String,
        conversationTitle: String,
        conversationId: String,
        messages: List<UIMessage>,
        now: LocalDateTime = LocalDateTime.now(),
    ): File? {
        if (messages.isEmpty()) return null
        val dir = conversationDir(context, assistantName, assistantId, conversationTitle, conversationId)
        if (!dir.exists() && !dir.mkdirs()) return null

        // 增量：只取上次存档之后的新消息
        val lastStamp = readLastStamp(dir)
        val increment = if (lastStamp == null) {
            messages
        } else {
            messages.filter { it.createdAt.toString() > lastStamp }
        }
        if (increment.isEmpty()) return null

        val nextIndex = (dir.listFiles().orEmpty()
            .mapNotNull { Regex("""压缩会话(\d+)""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0) + 1

        val file = File(dir, "压缩会话$nextIndex ${now.format(STAMP)}.md")
        val body = buildMarkdown(assistantName, conversationTitle, increment, now)
        return runCatching {
            file.writeText(body, Charsets.UTF_8)
            writeLastStamp(dir, messages.last().createdAt.toString())
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
        context: Context?,
        assistantId: String? = null,
        conversationId: String? = null,
    ): List<Entry> {
        val all = rootDir(context)
        val base = all
        // 带 conversationId 时，只留目录名以该 id 结尾的那一个（精确到会话）
        if (conversationId != null) {
            val suffix = "__${conversationId.take(8)}"
            val target = all.walkTopDown()
                .firstOrNull { it.isDirectory && it.name.endsWith(suffix) }
            if (target == null) return emptyList()
            return collectEntries(target)
        }
        if (assistantId != null) {
            val suffix = "__${assistantId.take(8)}"
            val target = all.listFiles().orEmpty()
                .firstOrNull { it.isDirectory && it.name.endsWith(suffix) }
            if (target == null) return emptyList()
            return collectEntries(target)
        }
        return collectEntries(base)
    }

    private fun collectEntries(base: File): List<Entry> {
        if (!base.exists()) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile && it.name.startsWith("压缩会话") && it.name.endsWith(".md") }
            .mapNotNull { file ->
                val index = Regex("""压缩会话(\d+)""").find(file.name)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                Entry(
                    file = file,
                    // 目录名形如 "<助手名>__<id8>"，把唯一标识后缀剥掉再给上层。
                    // 不剥的话，模型和用户在 history_search 的结果里看到的是
                    // "我的助手__a1b2c3d4" 这种既不像名字也不像 ID 的东西。
                    // 只剥末尾 8 位 id，用户名字里自带 "__" 的情况不受影响。
                    assistantName = stripIdSuffix(file.parentFile?.parentFile?.name.orEmpty()),
                    conversationTitle = stripIdSuffix(file.parentFile?.name.orEmpty()),
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
        context: Context?,
        keyword: String,
        limit: Int = 20,
        assistantId: String? = null,
        conversationId: String? = null,
        index: Int? = null,
    ): List<Hit> {
        if (keyword.isBlank()) return emptyList()
        val hits = mutableListOf<Hit>()
        for (entry in list(context, assistantId, conversationId)) {
            // 必须先按序号过滤，再花 IO 读文件。
            // 反过来会出事：limit 是全局配额，前面的存档（压缩会话1、2…）按名字排序
            // 永远排在前面，关键词一旦在它们里面也出现，配额就被吃满了，
            // 目标那份一条都分不到 —— 用户要查 3，返回的却是 1 和 2。
            if (index != null && entry.index != index) continue
            if (hits.size >= limit) break
            val text = runCatching { entry.file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            var perFile = 0
            for (line in text.lineSequence()) {
                if (perFile >= 5 || hits.size >= limit) break
                if (line.contains(keyword, ignoreCase = true) && line.isNotBlank()) {
                    hits += Hit(entry, line.trim().take(200))
                    perFile++
                }
            }
        }
        return hits
    }

    /**
     * 助手改名后同步目录名。
     *
     * 目录名是 "<名字>__<id8>"，改名时只换前半段，id 后缀原地不动——
     * 目录和助手是靠这 8 位关联的，不能跟着名字变。
     *
     * 找不到目录就直接返回：这个助手可能一次都没压缩过，没目录很正常。
     */
    fun onAssistantRenamed(context: Context?, assistantId: String, newName: String) {
        if (newName.isBlank()) return
        val root = rootDir(context)
        if (!root.exists()) return
        val suffix = "__${assistantId.take(8)}"
        val dir = root.listFiles().orEmpty()
            .firstOrNull { it.isDirectory && it.name.endsWith(suffix) }
            ?: return
        renameDir(dir, "${sanitize(newName)}$suffix")
    }

    /**
     * 会话改名后同步目录名。
     *
     * 会话目录埋在助手目录下面，这里不要求调用方知道是哪个助手：
     * 直接在整棵树里找以 "__<会话id8>" 结尾的那一个。
     */
    fun onConversationRenamed(context: Context?, conversationId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        val root = rootDir(context)
        if (!root.exists()) return
        val suffix = "__${conversationId.take(8)}"
        val dir = root.walkTopDown()
            .firstOrNull { it.isDirectory && it.name.endsWith(suffix) }
            ?: return
        renameDir(dir, "${sanitize(newTitle)}$suffix")
    }

    /**
     * 目录改名。撞名时宁可保持旧名字也不覆盖——丢存档比显示旧名字严重得多。
     * 跨挂载点 renameTo 会返回 false，退化成复制 + 删除。
     */
    private fun renameDir(dir: File, newName: String) {
        if (dir.name == newName) return
        val parent = dir.parentFile ?: return
        val target = File(parent, newName)
        if (target.exists()) return
        runCatching {
            if (!dir.renameTo(target)) {
                dir.copyRecursively(target, overwrite = false)
                dir.deleteRecursively()
            }
        }
    }

    /** 按会话 id 精确删除存档（目录名以 __<id8> 结尾）。 */
    fun deleteByConversationId(context: Context?, conversationId: String) {
        val root = rootDir(context)
        if (!root.exists()) return
        val suffix = "__${conversationId.take(8)}"
        root.walkTopDown()
            .filter { it.isDirectory && it.name.endsWith(suffix) }
            .toList()
            .forEach { runCatching { it.deleteRecursively() } }
    }

    /** 删除某个助手的全部存档。 */
    fun deleteAssistant(context: Context?, assistantId: String) {
        val root = rootDir(context)
        if (!root.exists()) return
        val suffix = "__${assistantId.take(8)}"
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(suffix) }
            .forEach { runCatching { it.deleteRecursively() } }
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
