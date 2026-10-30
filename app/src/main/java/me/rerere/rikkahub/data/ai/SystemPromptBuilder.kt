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
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                // 这两行以前是无条件加的，结果一个工具都没勾的纯聊天助手，
                // 提示词里照样写着 read_window_tree / browser_hook_create —— 模型真的会去调。
                // 现在按实际注册的工具名决定，没挂就别提。
                val hasExpensiveTools = toolNames.any {
                    it == "read_window_tree" || it == "browser_get_text" ||
                        it == "browser_screenshot" || it == "take_screenshot"
                }
                if (hasExpensiveTools) {
                    appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                }
                if (toolNames.any { it.startsWith("browser_") }) {
                    appendLine("Browser debugging order: browser_hook_create → browser_network_list / browser_hook_hits is the primary path and works on every device. browser_debug_attach (CDP) is optional; if it fails once on this device, stop retrying it and use hooks.")
                }
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
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
