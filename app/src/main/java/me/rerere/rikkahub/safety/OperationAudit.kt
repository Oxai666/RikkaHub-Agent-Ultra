package me.rerere.rikkahub.safety

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 操作审计。
 *
 * 每条记录写盘前过 [AuditRedactor] 脱敏——密码、token、私钥、连接串
 * 一旦进了文件就等于永久泄露，文件还会被备份工具带走。
 *
 * 持久化用 JSON（`files/agent-state/operation-audit.json`），跟 Goal/Plan 一个思路。
 * 环形缓冲保留最近 [MAX_RECORDS] 条：审计的用途是「刚才那几步到底干了什么」，
 * 不是长期归档。真要长期留痕应该走定期导出，那是另一个话题。
 */
object OperationAudit {

    const val MAX_RECORDS = 200

    private const val TAG = "OperationAudit"
    private const val DIR = "agent-state"
    private const val FILE = "operation-audit.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Record(
        val atMs: Long,
        val toolName: String,
        val tier: RiskTier,
        val decision: Decision,
        val blockCode: String? = null,
        /** 已脱敏的参数摘要。 */
        val argsDigest: String,
        val targetPaths: List<String> = emptyList(),
    )

    @Serializable
    enum class Decision {
        @SerialName("auto_allowed")
        AUTO_ALLOWED,

        @SerialName("approved_by_user")
        APPROVED_BY_USER,

        @SerialName("blocked")
        BLOCKED,

        @SerialName("failed")
        FAILED,
    }

    private val records = ConcurrentLinkedDeque<Record>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        load()
        loaded = true
    }

    private fun storeFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, FILE)
    }

    private fun load() {
        val file = storeFile() ?: return
        if (!file.isFile) return
        runCatching {
            val stored: List<Record> = json.decodeFromString(file.readText())
            // 文件里是新增在前的顺序，反着加进 deque 保证顺序一致
            stored.reversed().forEach { records.addFirst(it) }
            while (records.size > MAX_RECORDS) records.pollLast()
        }.onFailure {
            Log.w(TAG, "load failed, starting empty", it)
        }
    }

    private fun persist() {
        val file = storeFile() ?: return
        runCatching {
            file.writeText(json.encodeToString(records.toList()))
        }.onFailure {
            Log.w(TAG, "persist failed", it)
        }
    }

    fun record(
        toolName: String,
        tier: RiskTier,
        decision: Decision,
        argsDigest: String,
        blockCode: String? = null,
        targetPaths: List<String> = emptyList(),
    ) {
        records.addFirst(
            Record(
                atMs = System.currentTimeMillis(),
                toolName = toolName,
                tier = tier,
                decision = decision,
                blockCode = blockCode,
                argsDigest = AuditRedactor.redact(argsDigest),
                targetPaths = targetPaths,
            )
        )
        while (records.size > MAX_RECORDS) {
            records.pollLast()
        }
        persist()
    }

    fun recent(limit: Int = 50): List<Record> = records.take(limit)

    fun clear() {
        records.clear()
        persist()
    }
}

/**
 * 审计脱敏。
 *
 * 只处理最常见的那几种形态：`key=value`、`Authorization: Bearer xxx`、
 * PEM 私钥块、以及长串 hex/base64。不做深度语义分析——
 * 这是**降低泄露面**的措施，不是保密方案。
 */
object AuditRedactor {

    private val SECRET_KEYS = listOf(
        "password", "passwd", "secret", "token", "api_key", "apikey", "apisecret",
        "authorization", "auth", "cookie", "session", "private_key", "privatekey",
        "access_key", "secret_key", "bearer",
    )

    private val kvPattern = Regex(
        """([A-Za-z_]*(?:${SECRET_KEYS.joinToString("|")})[A-Za-z_]*)\s*[=:]\s*("[^"]*"|'[^']*'|\S+)""",
        RegexOption.IGNORE_CASE
    )

    private val bearerPattern = Regex("""(Bearer\s+)\S+""", RegexOption.IGNORE_CASE)

    private val pemPattern = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""
    )

    /** 长度 ≥ 32 的连续 hex/base64 串，多半是密钥或哈希。 */
    private val longSecretPattern = Regex("""[A-Za-z0-9+/=_-]{32,}""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = pemPattern.replace(out, "[REDACTED_PRIVATE_KEY]")
        out = bearerPattern.replace(out) { m -> m.groupValues[1] + "[REDACTED]" }
        out = kvPattern.replace(out) { m -> "${m.groupValues[1]}=[REDACTED]" }
        out = longSecretPattern.replace(out, "[REDACTED_LONG_VALUE]")
        return out
    }
}
