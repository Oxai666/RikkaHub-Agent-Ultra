package me.rerere.rikkahub.subagent

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 子智能体写租约。
 *
 * 派发时给定，运行期由工作区写工具强制执行 —— 这是闸门不是建议：
 *
 * | 取值 | 含义 |
 * |---|---|
 * | null（没设） | 不限制，保持旧行为 |
 * | `[]` | 只读，任何写入直接被拒 |
 * | `["*"]` | 独占整个工作区 |
 * | 具体路径 | 前缀匹配，越界一律拒 |
 *
 * 已知盲区（和万象一样，提示词里也这么写）：
 * **不覆盖 `workspace_shell`** —— `sed -i`、重定向、`mv`、`rm` 不受租约拦截。
 * 要严格隔离必须走 `workspace_write_file` / `workspace_edit_file`。
 */
data class SubAgentWriteLease(val paths: List<String>) {

    val readOnly: Boolean get() = paths.isEmpty()

    private val wholeWorkspace: Boolean get() = paths.any { it.trim() == "*" }

    fun allows(absoluteOrRelativePath: String): Boolean {
        if (readOnly) return false
        if (wholeWorkspace) return true
        val target = normalizeWorkspacePath(absoluteOrRelativePath)
        if (target.isEmpty()) return false
        return paths.any { raw ->
            val prefix = normalizeWorkspacePath(raw)
            prefix.isNotEmpty() && (target == prefix || target.startsWith("$prefix/"))
        }
    }

    fun describe(): String = when {
        readOnly -> "read-only (no writes allowed)"
        wholeWorkspace -> "full workspace write"
        else -> "write limited to: " + paths.joinToString(", ")
    }
}

/** `/workspace/a/./b/../c` → `a/c`。越界的 `..` 直接吞掉，不会回退到上级目录。 */
internal fun normalizeWorkspacePath(raw: String): String {
    var path = raw.trim().replace('\\', '/')
    if (path.startsWith("/workspace/")) path = path.removePrefix("/workspace/")
    else if (path == "/workspace") path = ""
    else if (path.startsWith("/")) path = path.removePrefix("/")
    val stack = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> stack.removeLastOrNull()
            else -> stack.addLast(segment)
        }
    }
    return stack.joinToString("/")
}

/** 运行期登记表：子智能体会话 id → 租约。写工具执行时按会话查。 */
object SubAgentWriteLeases {
    private val leases = ConcurrentHashMap<Uuid, SubAgentWriteLease>()

    fun attach(conversationId: Uuid, lease: SubAgentWriteLease) {
        leases[conversationId] = lease
    }

    fun detach(conversationId: Uuid) {
        leases.remove(conversationId)
    }

    fun of(conversationId: Uuid?): SubAgentWriteLease? =
        conversationId?.let { leases[it] }
}
