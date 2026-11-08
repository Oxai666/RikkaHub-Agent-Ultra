package me.rerere.rikkahub.safety

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 操作审计。
 *
 * 每条记录写库前过 [AuditRedactor] 脱敏——密码、token、私钥、连接串
 * 这些一旦进了数据库就等于永久泄露，日志文件会被备份工具带走。
 *
 * 这一版存在内存环形缓冲里（[MAX_RECORDS] 条封顶）：
 * 审计的主要用途是「刚才那几步到底干了什么」，不是长期归档。
 * 真要长期留痕，应该走 DB + 定期导出，那是另一个话题。
 */
object OperationAudit {

    const val MAX_RECORDS = 200

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

    enum class Decision {
        AUTO_ALLOWED,
        APPROVED_BY_USER,
        BLOCKED,
        FAILED,
    }

    private val records = ConcurrentLinkedDeque<Record>()

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
    }

    fun recent(limit: Int = 50): List<Record> = records.take(limit)

    fun clear() = records.clear()
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
