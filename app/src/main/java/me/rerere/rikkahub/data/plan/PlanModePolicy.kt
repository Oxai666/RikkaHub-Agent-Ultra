package me.rerere.rikkahub.data.plan

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ToolAccess

/**
 * Plan 模式的工具闸门。
 *
 * 这套东西的要点是：**只读靠的是过滤工具表，不是提示词**。
 * 模型再怎么被用户忽悠「你就帮我改一下」，它手上根本没有那个工具，
 * 调用的结果只能是「工具不存在」，而不是「我拒绝你」。
 *
 * 三条规则：
 *  1. `update_plan` 在 Plan 模式一律拒绝——它是执行期的检查表，
 *     混进规划期会让模型一边规划一边开干。
 *  2. Plan 自己的三件套（get/create/update plan）+ `ask_user` 放行。
 *  3. 其余只看 [ToolAccess.isReadOnly]。
 */
object PlanModePolicy {

    const val PLAN_MODE_READ_ONLY = "PLAN_MODE_READ_ONLY"
    const val PLAN_MODE_TOOL_FORBIDDEN = "PLAN_MODE_TOOL_FORBIDDEN"

    /** Plan 模式里额外放行的系统工具：规划自己 + 提问。 */
    private val PLAN_ALLOWED_SYSTEM_TOOLS: Set<String> = setOf(
        PlanToolCatalog.UPDATE_PLAN,
        "ask_user",
    )

    /** 这个工具在 Plan 模式下能不能用。 */
    fun isAllowedInPlan(toolName: String): Boolean {
        if (toolName == PlanToolCatalog.UPDATE_PLAN) return false
        if (toolName in PLAN_ALLOWED_SYSTEM_TOOLS) return true
        return ToolAccess.isReadOnly(toolName)
    }

    /**
     * 按模式裁剪工具表。非 Plan 模式原样返回。
     *
     * 注意这里返回的是**新列表**，调用方不要指望它跟传入的是同一个对象。
     */
    fun project(tools: List<Tool>, mode: CollaborationMode): List<Tool> {
        if (!mode.isPlan) return tools
        return tools.filter { isAllowedInPlan(it.name) }
    }

    fun rejectionCode(toolName: String): String =
        if (toolName == PlanToolCatalog.UPDATE_PLAN) PLAN_MODE_TOOL_FORBIDDEN else PLAN_MODE_READ_ONLY

    /** 给模型看的英文说明。 */
    fun rejectionMessage(toolName: String): String =
        if (toolName == PlanToolCatalog.UPDATE_PLAN) {
            "update_plan is a TODO/checklist tool and is not allowed in Plan mode"
        } else {
            "Tool `$toolName` is not available in Plan mode; Plan mode is read-only."
        }

    /** 给用户看的中文说明。 */
    fun rejectionUserMessage(toolName: String): String =
        if (toolName == PlanToolCatalog.UPDATE_PLAN) {
            "update_plan 不能在 Plan 模式调用；Plan 模式只产出计划，退出后执行阶段再更新检查表。"
        } else {
            "工具 `$toolName` 在 Plan 模式下不可用，Plan 模式只读。"
        }
}
