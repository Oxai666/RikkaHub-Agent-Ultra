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
 *  1. 系统工具放行表（见 [PLAN_ALLOWED_SYSTEM_TOOLS]）：`update_plan`
 *     + goal 三件套 + `ask_user`。
 *  2. `update_plan` 放行的理由见 [isAllowedInPlan] 的注释——
 *     不放行的话界面步骤条永远是空的。
 *  3. 其余只看 [ToolAccess.isReadOnly]。
 */
object PlanModePolicy {

    /** 唯一的拒绝原因：这个工具不在只读名单里。 */
    const val PLAN_MODE_READ_ONLY = "PLAN_MODE_READ_ONLY"

    /**
     * Plan 模式里额外放行的系统工具。
     *
     * 这些都是「改的是元数据，不是用户的东西」：计划表、目标、提问。
     * 真执行期的检查表和规划期的提纲是同一个工具，区别在提示词里的约束
     * （规划期不许标 in_progress），不在这个白名单上。
     */
    private val PLAN_ALLOWED_SYSTEM_TOOLS: Set<String> = setOf(
        // Plan 模式下也放行：用来列出「打算分几步走」，界面上的步骤条靠它。
        // 提示词里会写清楚这里不许标 in_progress。
        PlanToolCatalog.UPDATE_PLAN,
        "ask_user",
        "get_goal",
        "create_goal",
        "update_goal",
    )

    /**
     * 这个工具在 Plan 模式下能不能用。
     *
     * `update_plan` 原本一律拒绝——它是执行期的检查表，规划阶段调它会让
     * 模型一边规划一边开干。但实际用下来发现：Plan 模式下模型只会吐一段
     * `<proposed_plan>` 文本，界面上的步骤条永远是空的，用户完全看不到
     * 它打算分几步走。
     *
     * 所以放开：Plan 模式下允许 update_plan，但提示词里写清楚它在这里
     * 是「列提纲」用的，不许标 in_progress（那才叫边规划边执行）。
     */
    fun isAllowedInPlan(toolName: String): Boolean {
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

    /**
     * 从助手配置里读模式。
     *
     * 单独抽出来是因为过滤点有好几处（模型请求的工具表、本地执行用的
     * toolsInternal），每一处都自己解析一遍字符串容易写岔。
     */
    fun modeOf(assistant: me.rerere.rikkahub.data.model.Assistant): CollaborationMode =
        CollaborationMode.fromSerialName(assistant.collaborationMode)

    fun rejectionCode(toolName: String): String = PLAN_MODE_READ_ONLY

    /** 给模型看的英文说明。 */
    fun rejectionMessage(toolName: String): String =
        "Tool `$toolName` is not available in Plan mode; Plan mode is read-only."

    /** 给用户看的中文说明。 */
    fun rejectionUserMessage(toolName: String): String =
        "工具 `$toolName` 在 Plan 模式下不可用，Plan 模式只读。"
}
