package me.rerere.rikkahub.data.preferences

/**
 * Authoritative defaults and clamp helpers for every Termux-configurable knob. Mirrors
 * [me.rerere.rikkahub.browser.BrowserToolDefaults] in shape — one object, constants +
 * clamp functions. All ceilings are deliberate hard limits per the CLAUDE.md rule that
 * every tool MUST have a hard timeout.
 */
object TermuxDefaults {

    // --- Command capture timeout -----------------------------------------------------------
    /** Default ms to wait for a Termux background command to complete. */
    const val DEFAULT_COMMAND_TIMEOUT_MS = 60_000L   // 60 s
    const val MIN_COMMAND_TIMEOUT_MS     =  5_000L   //  5 s
    /** Hard ceiling: 10 min (600 s). Same as BrowserToolDefaults' per-tool max. */
    const val MAX_COMMAND_TIMEOUT_MS     = 600_000L  // 10 min

    // --- Per-turn wall-clock budget (app-wide) ---------------------------------------------
    // Default is 10 min matching the constant that was in GenerationHandler.kt.
    /** Default per-turn wall-clock budget in ms. */
    const val DEFAULT_TURN_BUDGET_MS = 10L * 60L * 1_000L  // 10 min
    const val MIN_TURN_BUDGET_MS     =  1L * 60L * 1_000L  //  1 min
    const val MAX_TURN_BUDGET_MS     = 60L * 60L * 1_000L  // 60 min

    // --- Per-turn tool-call step cap (app-wide) --------------------------------------------
    /**
     * 单轮允许的工具调用步数。
     *
     * **不设上限**。原来默认 32、硬顶 500，长任务（解包 → 改 smali → 回编 → 验链
     * 这类）跑着跑着就撞上，然后静默结束——表现跟「断连」一模一样。
     *
     * 现在默认和上限都放到 Int.MAX_VALUE，也就是实际意义上的不限制。
     * 真正防止失控的是另外两层：
     *   · MAX_LOOP_GUARD_TRIPS_PER_TURN：同一个工具反复被拦就停
     *   · 用户随时可以手动停止
     *
     * 设置页那个输入框仍然可以调小——想给自己留个保险的用户可以设成 20、50。
     */
    // 用 999999 而不是 Int.MAX_VALUE：设置页那个输入框 onValueChange 里
    // 有 .take(6)，超过 6 位的数字会被截断——Int.MAX_VALUE 会显示成 214748，
    // 看起来像个 bug。999999 步在任何实际场景下都等于无限。
    const val DEFAULT_MAX_TOOL_STEPS = 999_999
    const val MIN_MAX_TOOL_STEPS     =       1
    const val MAX_MAX_TOOL_STEPS     = 999_999

    // --- Verify smoke-test timeout ---------------------------------------------------------
    const val DEFAULT_VERIFY_TIMEOUT_MS =  8_000L   //  8 s
    const val MIN_VERIFY_TIMEOUT_MS     =  3_000L   //  3 s
    const val MAX_VERIFY_TIMEOUT_MS     = 30_000L   // 30 s

    // --- Working directory -----------------------------------------------------------------
    const val DEFAULT_WORKING_DIR = "/data/data/com.termux/files/home"

    // --- Stdout / stderr capture caps (bytes) ----------------------------------------------
    const val DEFAULT_MAX_STDOUT =  8_000
    const val MIN_MAX_STDOUT     =  1_000
    const val MAX_MAX_STDOUT     = 64_000

    const val DEFAULT_MAX_STDERR =  2_000
    const val MIN_MAX_STDERR     =    500
    const val MAX_MAX_STDERR     = 16_000

    // --- apt-wrap default ------------------------------------------------------------------
    /** ON by default — preserves the prior behavior for existing users. */
    const val DEFAULT_APT_WRAP_ENABLED = true

    // --- Max per-call timeout_seconds ceiling (LLM-exposed arg) ---------------------------
    /** Raised from 300 to 600 s so it aligns with the configurable command timeout ceiling. */
    const val MAX_COMMAND_TIMEOUT_SECONDS = 600

    // --- Clamp helpers ---------------------------------------------------------------------

    fun clampCommandTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_COMMAND_TIMEOUT_MS, MAX_COMMAND_TIMEOUT_MS)

    fun clampTurnBudgetMs(ms: Long): Long =
        ms.coerceIn(MIN_TURN_BUDGET_MS, MAX_TURN_BUDGET_MS)

    fun clampMaxToolSteps(steps: Int): Int =
        steps.coerceIn(MIN_MAX_TOOL_STEPS, MAX_MAX_TOOL_STEPS)

    fun clampVerifyTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_VERIFY_TIMEOUT_MS, MAX_VERIFY_TIMEOUT_MS)

    fun clampMaxStdout(bytes: Int): Int =
        bytes.coerceIn(MIN_MAX_STDOUT, MAX_MAX_STDOUT)

    fun clampMaxStderr(bytes: Int): Int =
        bytes.coerceIn(MIN_MAX_STDERR, MAX_MAX_STDERR)

    /**
     * Non-empty rule for the working directory. An empty value would pass a blank string to
     * Termux's RUN_COMMAND intent, which silently falls back to Termux's internal default —
     * a confusing outcome. Clamp by returning the default if the supplied value is blank.
     */
    fun clampWorkingDir(dir: String): String =
        dir.ifBlank { DEFAULT_WORKING_DIR }
}
