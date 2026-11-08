package me.rerere.rikkahub.safety

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.safety.ShellCommandRiskAnalyzer.analyze as analyzeShell

/**
 * 安全卡口。
 *
 * 所有工具执行前都过这里，产出 [RiskAssessment]，上层拿它决定：
 * 直接跑、出审批弹窗、还是拒绝。
 *
 * 跟现有 [me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard] 的关系：
 * 那个是**硬底线**（`rm -rf /` 这类永远拦，绕过所有审批），
 * 这个是**风险分级**（告诉你这条命令有多危险、会碰哪些文件）。
 * 两者串行，Hardline 先跑：硬底线拦掉的，这边根本不用算。
 */
object ToolSafetyCoordinator {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 评估一次工具调用。
     *
     * @param argsJson 工具参数的原始 JSON 字符串。解析失败不算错误——
     *        拿不到参数本身就是个信号，按"未知"处理更保守。
     */
    fun assess(
        toolName: String,
        argsJson: String,
        workspaceRoot: String? = null,
    ): RiskAssessment {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return RiskAssessment(
                toolName = toolName,
                baseTier = RiskTier.B_REVERSIBLE,
                tier = RiskTier.B_REVERSIBLE,
                reasons = listOf("参数无法解析，按可写处理"),
                reversible = false,
                requiresApproval = true,
                unknownTool = true,
            )

        val base = RiskAssessor.assess(toolName, args, workspaceRoot)

        // 快照拍不了但又是必须拍的 → 拒绝。这是"宁可不让做"的那条线。
        //
        // 加 targets.isNotEmpty() 这个条件很关键：参数名对不上时 pathsOf 会返回空，
        // 此时既没有要备份的东西、也谈不上"拍不了"，不该因此把操作拦死。
        // 没有 target 的情况交给审批弹窗，让人自己看一眼参数。
        if (base.snapshotPlan.required &&
            base.snapshotPlan.targets.isNotEmpty() &&
            !base.snapshotPlan.captureSupported &&
            !base.snapshotPlan.noSnapshotAllowed
        ) {
            return base.copy(
                blocked = true,
                blockCode = SafetyBlockCodes.SNAPSHOT_REQUIRED,
                blockMessage = "这个操作不可撤销，且当前环境无法为它拍快照，已拒绝",
            )
        }

        return base
    }

    /**
     * 给审批界面用的一行人话。
     *
     * 不让 UI 自己去拼——它没有 dry-run 计划和快照信息，
     * 拼出来的东西要么太笼统（"这个操作有风险"），要么吓人（一堆技术细节）。
     */
    fun approvalText(assessment: RiskAssessment): String = buildString {
        when (assessment.tier) {
            RiskTier.A_READ -> append("只读操作")
            RiskTier.B_REVERSIBLE -> append("可以撤销的操作")
            RiskTier.C_IRREVERSIBLE -> append("不可撤销的操作")
        }
        if (assessment.reasons.isNotEmpty()) {
            append("\n")
            append(assessment.reasons.joinToString("\n") { "· $it" })
        }
        if (assessment.dryRunPlan.targetPaths.isNotEmpty()) {
            append("\n涉及路径：")
            append(assessment.dryRunPlan.targetPaths.take(5).joinToString(", "))
            if (assessment.dryRunPlan.targetPaths.size > 5) {
                append(" 等 ${assessment.dryRunPlan.targetPaths.size} 个")
            }
        }
    }
}
