package me.rerere.rikkahub.data.ai.armor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 流式监看装饰器（对应破甲的闸④ + 闸⑤）。
 *
 * 设计原则：**只读不改**。
 *  - 不修改任何一个 chunk，原样 `emit` 给下游 —— 改内容会让 `StreamChunkHandler`
 *    的合并状态机错位，比不监看更糟
 *  - 不中断流 —— 断流用户拿到的是残废回答，比拒答还差
 *  - 任何异常都吞掉只记状态，绝不让监看把主流程打崩
 *
 * 判定结果写进 [ArmorState]，由 [ArmorTransformer] 在下一步决定要不要注入纠正。
 *
 * 注意：非流式路径（`streamOutput = false`）走 [generateText]，这里不做监看 ——
 * 拿不到增量，判定也就没有意义。那条路径的拒答属于已知盲区。
 */
class ArmorProvider(
    private val delegate: Provider<ProviderSetting>,
    private val state: ArmorState,
    private val conversationId: Uuid,
) : Provider<ProviderSetting> {

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
        delegate.listModels(providerSetting)

    override suspend fun getBalance(providerSetting: ProviderSetting): String =
        delegate.getBalance(providerSetting)

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = delegate.generateText(providerSetting, messages, params)

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = delegate.generateEmbedding(providerSetting, params)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = delegate.generateImage(providerSetting, params)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = delegate.editImage(providerSetting, params)

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val watch = state.resetWatch(conversationId)

        delegate.streamText(providerSetting, messages, params).collect { chunk ->
            watch.chunks += 1

            when (chunk) {
                is StreamChunk.ReasoningDelta -> runCatching { watch.appendReasoning(chunk.text) }
                is StreamChunk.TextDelta -> runCatching { watch.appendText(chunk.text) }
                is StreamChunk.Finish -> onFinish(watch, chunk.finishReason)
                else -> Unit
            }

            if (chunk !is StreamChunk.Finish) {
                runCatching { watch.verdict = ArmorJudge.judge(watch) }
            }

            emit(chunk)
        }
    }

    private fun onFinish(watch: ArmorWatch, reason: String?) {
        val normalized = reason?.lowercase() ?: return
        if (normalized !in TRUNCATION_REASONS) return
        watch.truncated = true
        watch.rawFinishReason = reason
        state.markTruncated(conversationId, reason)
    }

    companion object {
        /**
         * 上游把这些当成「非正常结束」。
         *
         * 各家取值不统一：OpenAI 用 `content_filter`，Anthropic 用 `refusal`，
         * 部分网关用 `blocked` / `guardrail` / `policy_violation`。
         * 这里只认「截断」，不认「正常结束」（stop / end_turn / STOP / completed 等）。
         */
        private val TRUNCATION_REASONS = setOf(
            "refusal",
            "safety",
            "content_filter",
            "content_filtered",
            "blocked",
            "guardrail",
            "policy_violation",
        )
    }
}
