package me.rerere.rikkahub.browser

/**
 * Authoritative list of the 17 browser tools the LLM can drive, plus their default
 * enabled/disabled state. Read tools (cheap, don't touch the page) default ON;
 * write tools (mutate state, can be misused) default OFF; the loop-control tool
 * defaults ON because the AI can't escape the browser loop without it.
 *
 * Pass 1 lays the catalogue. Pass 2's [me.rerere.rikkahub.data.ai.tools.LocalTools]
 * registration block will gate per-tool registration on these defaults via
 * [BrowserPreferences.isToolEnabled].
 */
object BrowserToolDefaults {

    // --- Read tools (default ON) --------------------------------------------------------------
    const val OPEN = "browser_open"
    const val CURRENT_URL = "browser_current_url"
    const val SCREENSHOT = "browser_screenshot"
    const val GET_TEXT = "browser_get_text"
    const val GET_DOM = "browser_get_dom"
    const val GET_LINKS = "browser_get_links"
    const val BACK = "browser_back"
    const val FORWARD = "browser_forward"
    const val WAIT_FOR = "browser_wait_for"
    /** CDP 探针：列当前进程的 WebView 调试目标。只读、不碰页面，默认开。 */
    const val DEBUG_TARGETS = "browser_debug_targets"
    /** Hook 规则管理：读类默认开，写类默认关。 */
    const val HOOK_LIST = "browser_hook_list"
    const val HOOK_HITS = "browser_hook_hits"
    const val NETWORK_LIST = "browser_network_list"
    /** CDP 断点调试：读类默认开，写类默认关。 */
    const val DEBUG_STATUS = "browser_debug_status"
    const val DEBUG_LIST_BREAKPOINTS = "browser_debug_list_breakpoints"
    const val DEBUG_STATE = "browser_debug_state"
    const val DEBUG_ATTACH = "browser_debug_attach"
    const val DEBUG_SET_BREAKPOINT = "browser_debug_set_breakpoint"
    const val DEBUG_REMOVE_BREAKPOINT = "browser_debug_remove_breakpoint"
    const val DEBUG_RESUME = "browser_debug_resume"
    const val DEBUG_STEP = "browser_debug_step"
    const val DEBUG_DETACH = "browser_debug_detach"
    const val HOOK_CREATE = "browser_hook_create"
    const val HOOK_REMOVE = "browser_hook_remove"

    // --- Write tools (default OFF) -----------------------------------------------------------
    const val CLICK = "browser_click"
    const val TYPE = "browser_type"
    const val SCROLL = "browser_scroll"
    const val SUBMIT = "browser_submit"
    const val SELECT = "browser_select"
    const val PRESS_KEY = "browser_press_key"
    const val EVAL_JS = "browser_eval_js"
    /** 通过 CDP 会话执行 JS。写类（能读到 cookie），默认关。 */
    const val DEBUG_EVAL = "browser_debug_eval"
    /**
     * Token-cost optimisation pass — composite "click + read" tool. One round trip
     * instead of click → wait → get_text. Default OFF: it's still a write tool, the
     * click side carries the same trust footprint as plain browser_click.
     */
    const val CLICK_AND_READ = "browser_click_and_read"

    // --- Loop control (default ON) -----------------------------------------------------------
    const val DONE = "browser_done"

    val READ_TOOLS: Set<String> = setOf(
        OPEN, CURRENT_URL, SCREENSHOT, GET_TEXT, GET_DOM, GET_LINKS, BACK, FORWARD, WAIT_FOR,
        DEBUG_TARGETS,
        HOOK_LIST,
        HOOK_HITS,
        NETWORK_LIST,
        DEBUG_STATUS,
        DEBUG_LIST_BREAKPOINTS,
        DEBUG_STATE,
    )

    val WRITE_TOOLS: Set<String> = setOf(
        CLICK, TYPE, SCROLL, SUBMIT, SELECT, PRESS_KEY, EVAL_JS, CLICK_AND_READ, DEBUG_EVAL,
        HOOK_CREATE, HOOK_REMOVE,
        DEBUG_ATTACH, DEBUG_SET_BREAKPOINT, DEBUG_REMOVE_BREAKPOINT,
        DEBUG_RESUME, DEBUG_STEP, DEBUG_DETACH,
    )

    val LOOP_CONTROL_TOOLS: Set<String> = setOf(DONE)

    /** Stable display order for the Settings page. Read first, then write, then loop-control. */
    val ALL_TOOLS: List<String> = listOf(
        OPEN, CURRENT_URL, SCREENSHOT, GET_TEXT, GET_DOM, GET_LINKS, BACK, FORWARD, WAIT_FOR,
        DEBUG_TARGETS,
        HOOK_LIST,
        HOOK_HITS,
        NETWORK_LIST,
        DEBUG_STATUS,
        DEBUG_LIST_BREAKPOINTS,
        DEBUG_STATE,
        CLICK, TYPE, SCROLL, SUBMIT, SELECT, PRESS_KEY, EVAL_JS, CLICK_AND_READ,
        DEBUG_EVAL,
        HOOK_CREATE, HOOK_REMOVE,
        DEBUG_ATTACH, DEBUG_SET_BREAKPOINT, DEBUG_REMOVE_BREAKPOINT,
        DEBUG_RESUME, DEBUG_STEP, DEBUG_DETACH,
        DONE,
    )

    /**
     * Defaults baked in. Read tools + the loop-control tool default ON; write tools
     * default OFF — matches the spec's "highest-trust surface — user must explicitly
     * grant write access" rule.
     */
    val DEFAULT_ENABLED: Map<String, Boolean> = buildMap {
        READ_TOOLS.forEach { put(it, true) }
        LOOP_CONTROL_TOOLS.forEach { put(it, true) }
        WRITE_TOOLS.forEach { put(it, false) }
    }

    enum class Category { READ, WRITE, LOOP_CONTROL }

    fun category(toolName: String): Category = when {
        toolName in WRITE_TOOLS -> Category.WRITE
        toolName in LOOP_CONTROL_TOOLS -> Category.LOOP_CONTROL
        else -> Category.READ
    }

    // --- Timeout settings (GitHub issue #4: user-configurable) -------------------------------
    //
    // The issue asked for "unlimited", but a truly unbounded task can wedge the Telegram bot
    // (it blocks on generation completing) and burn battery silently — see CLAUDE.md's "every
    // tool MUST have a hard timeout" rule. So instead of unlimited we expose a generous
    // configurable range with a hard ceiling. Input from the Settings UI is clamped to these
    // bounds via [clampPerToolTimeoutMs] / [clampSingleTaskTimeoutMs].

    /** Per-tool timeout (`withTimeoutOrNull` around each browser tool dispatch). */
    const val DEFAULT_PER_TOOL_TIMEOUT_MS = 30_000L
    const val MIN_PER_TOOL_TIMEOUT_MS = 10_000L          // 10 s
    const val MAX_PER_TOOL_TIMEOUT_MS = 600_000L         // 10 min

    /** Single-task window (hard cap on one AI-driven browser task to bound runaway loops). */
    const val DEFAULT_SINGLE_TASK_TIMEOUT_MS = 300_000L  // 5 min
    const val MIN_SINGLE_TASK_TIMEOUT_MS = 60_000L       // 1 min
    const val MAX_SINGLE_TASK_TIMEOUT_MS = 3_600_000L    // 60 min

    /** Clamp a per-tool timeout (ms) into the supported range. */
    fun clampPerToolTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_PER_TOOL_TIMEOUT_MS, MAX_PER_TOOL_TIMEOUT_MS)

    /** Clamp a single-task timeout (ms) into the supported range. */
    fun clampSingleTaskTimeoutMs(ms: Long): Long =
        ms.coerceIn(MIN_SINGLE_TASK_TIMEOUT_MS, MAX_SINGLE_TASK_TIMEOUT_MS)
}
