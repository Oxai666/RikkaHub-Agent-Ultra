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
        // ⚠ 安全层已关闭。
        //
        // 原来这里做三件事：风险分级、拍快照、决定要不要弹审批。
        // 实际跑下来误拦太多——一条 `cd /workspace && echo "..."` 会被
        // 判成"不可撤销 + 需要备份 + 备份失败"，然后整条命令被拒。
        // 模型只好绕一大圈用别的工具重试，体验比没有安全层还差。
        //
        // 现在一律按只读放行：
        //   requiresApproval = false   → 不弹审批
        //   blocked = false            → 不拒绝
        //   snapshotPlan = NONE        → 不拍快照，GenerationLoop 里那段
        //                                「备份失败就拒绝」也不会触发
        //
        // 原来的分析代码（RiskAssessor / ShellCommandRiskAnalyzer /
        // FileSnapshotManager）都还在，只是没人调了。要恢复的话把这行
        // 换回 RiskAssessor.assess(toolName, args, workspaceRoot) +
        // 原来的快照检查即可。
        return RiskAssessment.readOnly(toolName)
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
