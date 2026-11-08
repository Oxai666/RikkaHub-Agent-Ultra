package me.rerere.rikkahub.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        /** 本次真正注册的工具名。用来判断提示词里提到的东西存不存在。 */
        toolNames: Set<String> = emptySet(),
        /**
         * Plan 模式的整段提示词。非空时整段拼在助手提示词之后。
         * 内容来自 `assets/prompts/PLAN_MODE.md`，由调用方读取后传进来——
         * 这个 builder 是纯函数，不碰 Context。
         */
        planModePrompt: String? = null,
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (!planModePrompt.isNullOrBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append(planModePrompt.trim())
            }
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                // 这几条以前是硬编码整句塞进去的，结果：
                //  - 一个工具都没勾的纯聊天助手，提示词里写着 read_window_tree、browser_hook_create；
                //  - 只勾了 take_screenshot 的助手，也被告知「优先用 browser_get_text」。
                // 模型看到不存在的函数名会真的去调，然后拿到 tool_not_found。
                //
                // 现在按**实际注册的工具名**逐条拼：句子里出现的每个名字都保证存在，
                // 条件不满足就整条不出现。
                val cheapReaders = listOf("read_window_tree", "browser_get_text").filter { it in toolNames }
                val expensiveReaders = listOf("take_screenshot", "browser_screenshot").filter { it in toolNames }
                if (cheapReaders.isNotEmpty() && expensiveReaders.isNotEmpty()) {
                    appendLine(
                        "Tool cost guidance: prefer ${cheapReaders.joinToString("/")} before " +
                            "${expensiveReaders.joinToString("/")} when text is enough, and avoid repeating " +
                            "high-cost tools unless the state likely changed."
                    )
                }

                // 钩子链路提示：先挂钩子，再读结果。只有 hook_create 存在才有意义，
                // 而里面提到的读取工具也只列真实存在的那些。
                if ("browser_hook_create" in toolNames) {
                    val readers = listOf("browser_network_list", "browser_hook_hits")
                        .filter { it in toolNames }
                    if (readers.isNotEmpty()) {
                        appendLine(
                            "Browser debugging order: browser_hook_create → ${readers.joinToString(" / ")} " +
                                "is the primary path and works on every device."
                        )
                    }
                }

                // CDP 的降级提示单独一条：只有真的挂了 browser_debug_attach 才需要它。
                if ("browser_debug_attach" in toolNames) {
                    appendLine(
                        "browser_debug_attach (CDP) is optional; if it fails once on this device, " +
                            "stop retrying it and use hooks instead."
                    )
                }
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }

            // 技能沉淀引导。只在模型真的能写技能时才加——
            // 工具不在还让它「建议用户保存」，只会得到一句没法兑现的空话。
            if ("skill_create" in toolNames) {
                if (isNotEmpty()) appendLine()
                append(SKILL_CAPTURE_HINT)
            }
        }.trim()

        val volatile = buildString {
            if (memoryPrompt.isNotBlank()) append(memoryPrompt)
            if (recentChatsPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(recentChatsPrompt)
            }
            if (!systemAddendum.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(systemAddendum)
            }
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt, memoryPrompt, recentChatsPrompt, toolPrompts, systemAddendum
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}

/**
 * 技能沉淀引导。
 *
 * 只在 `skill_create` 真的注册进工具表时才拼进系统提示词（见 buildSections）。
 *
 * 写的三条判断标准是刻意具体的：不写「有价值的任务」这种没法执行的说法，
 * 而是给可对照的信号。否则模型要么见什么都想存，要么从来想不起来存。
 */
private val SKILL_CAPTURE_HINT = """
## Capturing reusable work as a skill

When a task took real effort to figure out — a reverse-engineering target, a pentest
workflow, a debugging sequence that finally worked — end your reply with ONE short
question asking whether the user wants it saved as a reusable skill.

Worth capturing:
- it took 5+ steps before you found the right path
- the failures taught you something specific (a check that fired, a format that broke)
- next time the same target class shows up, the steps would be the same

Not worth capturing: one-shot simple operations, plain lookups, ad-hoc commands that
only make sense for this one file.

If the user says yes, call `skill_list` first to see existing categories, then
`skill_create`. In `content`, write the commands you ACTUALLY ran and what each step
told you — not generic advice. Note the dead ends too; that is usually the most useful
part.

Never create a skill without asking first, and never clean up skills on your own.
""".trimIndent()
