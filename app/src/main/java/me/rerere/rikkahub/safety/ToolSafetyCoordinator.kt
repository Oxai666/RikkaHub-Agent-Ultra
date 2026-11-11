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
        // 参数解析不出来就按只读放行，不因为「看不懂参数」把操作拦死。
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return RiskAssessment.readOnly(toolName)

        // ⚠ 「快照拍不了就拒绝」那条已经删了。
        //
        // 原来这里是：snapshotPlan.required && targets 非空 && 拍不了 → blocked。
        // 误伤太多——一条 `cd /workspace && echo "..."` 会被判成不可撤销 + 需要备份，
        // 然后因为沙箱路径翻译不上而"备份失败"，整条命令被拒。
        //
        // 现在只做风险分级，不做「拍不了就拒」。快照该拍还是会拍
        // （GenerationLoop 里那段），拍不到就当没拍到，照常执行。
        return RiskAssessor.assess(toolName, args, workspaceRoot)
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
