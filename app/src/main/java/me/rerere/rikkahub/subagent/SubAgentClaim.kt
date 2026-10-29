package me.rerere.rikkahub.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 子智能体的"完成声明"（claim）与其核验。
 *
 * 没有这一层的时候，子智能体说"已完成"父智能体就只能信 —— 实测最常见的假阳性是
 * "写完了但是没落盘"和"命令跑了但报错被忽略"。这里把完成声明变成可核对的三类凭据：
 *
 *  - `files`        路径必须真实存在（相对工作区，越界路径直接算失败）
 *  - `verification` 命令原文保留，但**不在父级自动执行** —— 自动跑子智能体给的命令等于
 *                   绕过审批层，收益不足以抵消风险，因此标 UNVERIFIED 交给父级/用户复核
 *  - `manual`       只能由人确认的事项，永远不背书
 *
 * 自报 `complete` 但凭据不足（有失败项、或全是未核验项）时，有效状态降级为 `partial`，
 * 这正是这套设计最值钱的地方：自报的不算数。
 */

@Serializable
data class SubAgentClaim(
    val status: String = "complete",
    val summary: String = "",
    @SerialName("acceptance_criteria")
    val criteria: List<SubAgentCriterion> = emptyList(),
)

@Serializable
data class SubAgentCriterion(
    val type: String = "",
    val claim: String = "",
    val command: String? = null,
    val paths: List<String> = emptyList(),
)

enum class ClaimVerdict { VERIFIED, FAILED, UNVERIFIED, MANUAL }

data class CriterionReview(
    val criterion: SubAgentCriterion,
    val verdict: ClaimVerdict,
    val detail: String,
)

data class ClaimReview(
    val reportedStatus: String,
    val effectiveStatus: String,
    val criteria: List<CriterionReview>,
    /** 给父智能体/用户看的一段纯文本，会拼到 run.result 末尾。 */
    val report: String,
)

private val claimJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 从最终回复里抽出最后一个能解析的 claim 代码块；没有就返回 null。 */
fun parseSubAgentClaim(text: String): SubAgentClaim? {
    if (text.isBlank()) return null
    val blocks = extractJsonBlocks(text)
    for (block in blocks.asReversed()) {
        val parsed = runCatching { claimJson.decodeFromString<SubAgentClaim>(block) }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

/**
 * 核验 claim。
 *
 * @param workspaceCwd 子智能体的工作区根；null 表示没有可用工作区，此时文件类凭据一律
 *   UNVERIFIED（不猜绝对路径 —— 猜错会把"文件在别处"误判成失败）。
 * @param fileExists 注入点，单测里用它替掉真实文件系统。
 */
fun reviewSubAgentClaim(
    finalText: String,
    workspaceCwd: String?,
    fileExists: (String) -> Boolean = { path ->
        val root = workspaceCwd
        root != null && runCatching { File(root, path).isFile }.getOrDefault(false)
    },
): ClaimReview? {
    val claim = parseSubAgentClaim(finalText) ?: return null
    val reported = claim.status.trim().lowercase().ifEmpty { "complete" }

    val reviews = claim.criteria.map { criterion ->
        when (criterion.type.trim().lowercase()) {
            "files" -> reviewFiles(criterion, workspaceCwd, fileExists)
            "verification" -> CriterionReview(
                criterion = criterion,
                verdict = ClaimVerdict.UNVERIFIED,
                detail = "命令未自动执行（避免父级跑子智能体给的任意命令），需父级或用户复核：" +
                    (criterion.command?.takeIf { it.isNotBlank() } ?: "（未给出命令原文）"),
            )
            "manual" -> CriterionReview(
                criterion = criterion,
                verdict = ClaimVerdict.MANUAL,
                detail = "只能由用户确认：" + criterion.claim.ifBlank { "（未说明）" },
            )
            else -> CriterionReview(
                criterion = criterion,
                verdict = ClaimVerdict.UNVERIFIED,
                detail = "未知凭据类型 \"${criterion.type}\"，按未核验处理",
            )
        }
    }

    val failures = reviews.count { it.verdict == ClaimVerdict.FAILED }
    val verified = reviews.count { it.verdict == ClaimVerdict.VERIFIED }
    val backed = reviews.count { it.verdict == ClaimVerdict.VERIFIED || it.verdict == ClaimVerdict.MANUAL }

    val effective = when {
        reported == "failed" -> "failed"
        failures > 0 -> "partial"
        reported == "complete" && reviews.isEmpty() -> "partial"
        // 自报完成但一条硬凭据都没有（全是命令/人工项）→ 降级，别让"我跑过了"当结论
        reported == "complete" && verified == 0 && backed == 0 -> "partial"
        else -> reported
    }

    val header = buildString {
        append("[claim] ")
        append(if (reported == effective) reported else "$reported → $effective")
        append(" · ")
        append("$verified verified")
        if (failures > 0) append(" / $failures failed")
        val unverified = reviews.count { it.verdict == ClaimVerdict.UNVERIFIED }
        if (unverified > 0) append(" / $unverified unverified")
        val manual = reviews.count { it.verdict == ClaimVerdict.MANUAL }
        if (manual > 0) append(" / $manual manual")
    }

    val body = reviews.joinToString("\n") { review ->
        val tag = when (review.verdict) {
            ClaimVerdict.VERIFIED -> "VERIFIED"
            ClaimVerdict.FAILED -> "FAILED"
            ClaimVerdict.UNVERIFIED -> "UNVERIFIED"
            ClaimVerdict.MANUAL -> "MANUAL"
        }
        "- ${review.criterion.type.ifBlank { "unknown" }}: $tag — ${review.detail}"
    }

    val report = buildString {
        append(header)
        if (claim.summary.isNotBlank()) {
            append("\n- 子智能体自述：")
            append(claim.summary.trim())
        }
        if (body.isNotBlank()) {
            append('\n')
            append(body)
        }
        if (effective != reported) {
            append("\n- 结论：自报 ")
            append(reported)
            append("，凭据不足以支撑，按 ")
            append(effective)
            append(" 处理")
        }
    }

    return ClaimReview(
        reportedStatus = reported,
        effectiveStatus = effective,
        criteria = reviews,
        report = report,
    )
}

private fun reviewFiles(
    criterion: SubAgentCriterion,
    workspaceCwd: String?,
    fileExists: (String) -> Boolean,
): CriterionReview {
    if (workspaceCwd == null) {
        return CriterionReview(
            criterion = criterion,
            verdict = ClaimVerdict.UNVERIFIED,
            detail = "没有可用工作区，无法核对文件是否落盘：" + criterion.paths.joinToString(", "),
        )
    }
    if (criterion.paths.isEmpty()) {
        return CriterionReview(
            criterion = criterion,
            verdict = ClaimVerdict.FAILED,
            detail = "声明了 files 凭据但没有给出任何路径",
        )
    }
    val missing = criterion.paths.filterNot { raw ->
        val path = raw.trim()
        if (path.isEmpty()) return@filterNot false
        // 绝对路径只认工作区内写法 /workspace/…（模型习惯这么写，真机上确实是真的）；
        // 其它绝对路径（/etc/passwd 之类）一律算越界。
        val insideWorkspace = path == "/workspace" || path.startsWith("/workspace/")
        if (path.startsWith("/") && !insideWorkspace) return@filterNot false
        if (path.split('/', '\\').contains("..")) return@filterNot false
        val relative = normalizeWorkspacePath(path)
        relative.isNotEmpty() && fileExists(relative)
    }
    return if (missing.isEmpty()) {
        CriterionReview(
            criterion = criterion,
            verdict = ClaimVerdict.VERIFIED,
            detail = "已落盘：" + criterion.paths.joinToString(", "),
        )
    } else {
        CriterionReview(
            criterion = criterion,
            verdict = ClaimVerdict.FAILED,
            detail = "缺失或不在工作区内：" + missing.joinToString(", "),
        )
    }
}

/** 抓取所有 ```json 围栏块的内容，按出现顺序返回。 */
internal fun extractJsonBlocks(text: String): List<String> {
    val blocks = mutableListOf<String>()
    var index = 0
    while (true) {
        val fence = text.indexOf("```json", index, ignoreCase = true)
        if (fence < 0) break
        val contentStart = text.indexOf('\n', fence).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf("```", contentStart)
        if (end < 0) break
        blocks += text.substring(contentStart, end)
        index = end + 3
    }
    return blocks
}
