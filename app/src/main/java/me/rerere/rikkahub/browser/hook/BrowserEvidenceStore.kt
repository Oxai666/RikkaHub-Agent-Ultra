package me.rerere.rikkahub.browser.hook

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条证据。抓到的每一个请求/响应、console 输出、hook 命中都可以落成一条。
 *
 * 和 [NetworkBodyStore] 的分工：
 *  - NetworkBodyStore 是**运行期缓存**，6MB 上限、LRU 淘汰、进程死就没了；
 *  - 这里是**磁盘归档**，重启 App 之后还能翻，用来复盘「刚才那个页面到底发了什么」。
 */
@Serializable
data class EvidenceEntry(
    val id: String,
    val at: Long = System.currentTimeMillis(),
    /** network | console | hit | snapshot */
    val kind: String,
    val url: String = "",
    val method: String = "",
    val status: Int = 0,
    val durationMs: Long = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseBody: String = "",
    val ruleId: String = "",
    val actionTaken: String = "",
    val note: String = "",
)

/**
 * 证据库：按 tab 分文件，JSONL 追加写。
 *
 * 设计取舍：
 *  - **不做数据库**。证据是只追加的时间序列，查询永远是「最近 N 条」或「某个 URL」，
 *    Room 那套 schema/迁移成本换不来收益；
 *  - **单写协程**。页面上可能同时有一堆请求打进来，多协程抢同一个文件会写出半行；
 *    这里用 Channel 串行化，落盘顺序 = 产生顺序；
 *  - **超限截断而不是轮转**。单文件超过 [maxFileBytes] 时保留后半部分 ——
 *    复盘要的是「最近发生了什么」，老证据价值低于实现复杂度；
 *  - **body 单独截断**。一条几十 KB 的响应体足以把整个文件吃满，
 *    按 [maxBodyChars] 截断，并在 note 里标出原始长度。
 */
class BrowserEvidenceStore(
    private val context: Context,
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxBodyChars: Int = 32 * 1024,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val dir: File get() = File(context.filesDir, "browser-evidence").apply { mkdirs() }

    private val writeQueue = Channel<Pair<String, EvidenceEntry>>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for ((tabId, entry) in writeQueue) {
                runCatching { appendLocked(tabId, entry) }
            }
        }
    }

    /** 非阻塞入队；写盘失败/队列满都不该影响页面。 */
    fun record(tabId: String, entry: EvidenceEntry) {
        writeQueue.trySend(tabId.ifBlank { "default" } to entry)
    }

    private fun fileFor(tabId: String): File =
        File(dir, "${tabId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.jsonl")

    private fun appendLocked(tabId: String, entry: EvidenceEntry) {
        val safe = entry.copy(
            requestBody = clampBody(entry.requestBody),
            responseBody = clampBody(entry.responseBody),
        )
        val line = json.encodeToString(EvidenceEntry.serializer(), safe)
        val f = fileFor(tabId)
        f.appendText(line + "\n")

        if (f.length() > maxFileBytes) {
            // 保留后半部分：按行切，从中间往后找第一个完整行
            val lines = f.readLines()
            val keep = lines.takeLast((lines.size / 2).coerceAtLeast(1))
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    private fun clampBody(body: String): String {
        if (body.length <= maxBodyChars) return body
        return body.take(maxBodyChars) + "…[truncated, total ${body.length} chars]"
    }

    /** 读最近 [limit] 条；[since] 非空时只取 at > since 的。 */
    suspend fun read(tabId: String, limit: Int = 50, since: Long? = null): List<EvidenceEntry> =
        withContext(Dispatchers.IO) {
            val f = fileFor(tabId.ifBlank { "default" })
            if (!f.exists()) return@withContext emptyList()
            runCatching {
                val lines = f.readLines()
                val from = lines.indexOfLast { it.isNotBlank() } - limit + 1
                lines.asSequence()
                    .drop(from.coerceAtLeast(0))
                    .filter { it.isNotBlank() }
                    .mapNotNull { runCatching { json.decodeFromString(EvidenceEntry.serializer(), it) }.getOrNull() }
                    .filter { since == null || it.at > since }
                    .toList()
            }.getOrDefault(emptyList())
        }

    /** 所有 tab 的文件清单 + 大小，给工具层做概览。 */
    suspend fun stats(): List<FileStat> = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .map { f ->
                val lines = runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(0)
                FileStat(tabId = f.nameWithoutExtension, bytes = f.length(), entries = lines)
            }
            .sortedByDescending { it.bytes }
    }

    /** 清空某个 tab 的证据；tabId 为空则全清。 */
    suspend fun clear(tabId: String? = null) = withContext(Dispatchers.IO) {
        if (tabId.isNullOrBlank()) {
            dir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
        } else {
            runCatching { fileFor(tabId).delete() }
        }
    }

    data class FileStat(val tabId: String, val bytes: Long, val entries: Int)
}
