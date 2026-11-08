package me.rerere.rikkahub.data.plan

/**
 * `update_plan` 工具的硬约束与 schema 文案。
 *
 * 这些数字不是随便定的：步骤上限 12 是为了让计划能一眼看完，
 * 单步 120 字是为了逼模型把一句话说清楚而不是写成小作文。
 */
object PlanToolCatalog {

    const val UPDATE_PLAN = "update_plan"

    const val MIN_STEPS = 1
    const val MAX_STEPS = 12
    const val MIN_STEP_CHARS = 1
    const val MAX_STEP_CHARS = 120
    const val MAX_EXPLANATION_CHARS = 500

    /**
     * 工具描述。
     *
     * 刻意写死成常量：这段文字一改，模型对工具的用法就跟着变，
     * 想要不同的行为就得有意识地改这里，不能顺手在别处加一句。
     *
     * **数字写进描述里**。schema 里只有 "string"，模型看不到长度上限，
     * 会一路写到 300 字然后被校验拒掉，白烧一轮。把上限明写出来，
     * 它第一次就会照着砍。
     */
    const val DESCRIPTION =
        "Maintain the task plan shown above the input box. " +
            "Call it when: starting work that needs 3+ distinct steps, after finishing each step " +
            "(mark it completed), and once more at the end with every step completed. " +
            "Skip it for one-step or purely conversational replies. " +
            "Hard limits: ${MIN_STEPS}-${MAX_STEPS} steps, each step at most ${MAX_STEP_CHARS} characters, " +
            "explanation at most ${MAX_EXPLANATION_CHARS} characters, " +
            "status is one of pending / in_progress / completed, and at most one step may be " +
            "in_progress at any time. " +
            "Always resend the FULL list — this replaces the previous plan, it does not append. " +
            "In Plan mode, list the outline only and never mark a step in_progress."

    /** 拒绝原因码。 */
    const val ERR_TOO_FEW_STEPS = "PLAN_TOO_FEW_STEPS"
    const val ERR_TOO_MANY_STEPS = "PLAN_TOO_MANY_STEPS"
    const val ERR_STEP_TOO_LONG = "PLAN_STEP_TOO_LONG"
    const val ERR_STEP_BLANK = "PLAN_STEP_BLANK"
    const val ERR_TOO_MANY_IN_PROGRESS = "PLAN_TOO_MANY_IN_PROGRESS"
    const val ERR_BAD_STATUS = "PLAN_BAD_STATUS"
    const val ERR_EXPLANATION_TOO_LONG = "PLAN_EXPLANATION_TOO_LONG"
}
