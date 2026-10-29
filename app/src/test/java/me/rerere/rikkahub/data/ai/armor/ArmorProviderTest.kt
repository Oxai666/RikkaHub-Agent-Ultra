package me.rerere.rikkahub.data.ai.armor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [ArmorProvider] 的透传契约。
 *
 * 它是整套 armor 里唯一贴着真实网络流的一层。它出错有两种方式，都很隐蔽：
 *  1. 改了 chunk 内容 —— 下游 StreamChunkHandler 的合并状态机会错位
 *  2. 吞了异常 —— 真断流被当成正常结束，用户拿到一个不完整却「看起来成功」的回答
 * 所以这里重点锁：**chunk 原样透传** 与 **异常原样上抛**。
 */
class ArmorProviderTest {

    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "gpt-test", displayName = "Test")
    private val params = TextGenerationParams(model = model)
    private val conv = Uuid.random()

    private fun armor(chunks: Flow<StreamChunk>, state: ArmorState = ArmorState()) =
        ArmorProvider(ChunkProvider(chunks), state, conv)

    // ------------------------------------------------------------------
    // 只读不改
    // ------------------------------------------------------------------

    @Test
    fun `every chunk passes through untouched and in order`() = runTest {
        val input = listOf<StreamChunk>(
            StreamChunk.ReasoningStart("r1"),
            StreamChunk.ReasoningDelta("r1", "先想一想"),
            StreamChunk.ReasoningEnd("r1"),
            StreamChunk.TextStart("t1"),
            StreamChunk.TextDelta("t1", "第一段"),
            StreamChunk.TextDelta("t1", "第二段"),
            StreamChunk.TextEnd("t1"),
            StreamChunk.ToolCallStart("c1", "search"),
            StreamChunk.ToolCallDelta("c1", inputDelta = "{\"q\":"),
            StreamChunk.ToolCallEnd("c1"),
            StreamChunk.Finish("stop"),
        )

        val out = armor(flow { input.forEach { emit(it) } })
            .streamText(setting, emptyList(), params)
            .toList()

        assertEquals("数量、顺序、内容都不许变", input, out)
    }

    @Test
    fun `reasoning and text land in their own buckets`() = runTest {
        val state = ArmorState()

        armor(
            flow {
                emit(StreamChunk.ReasoningDelta("r1", "推理片段"))
                emit(StreamChunk.TextDelta("t1", "正文片段"))
                emit(StreamChunk.TextDelta("t1", "再来一段"))
            },
            state,
        ).streamText(setting, emptyList(), params).toList()

        val watch = state.watch(conv)
        assertEquals("推理链归推理桶", "推理片段", watch.reasoning.toString())
        assertEquals("正文归正文桶", "正文片段再来一段", watch.text.toString())
        assertEquals("每个 chunk 都要计数", 3, watch.chunks)
    }

    @Test
    fun `unrelated chunks still count but do not touch the buffers`() = runTest {
        val state = ArmorState()

        armor(
            flow {
                emit(StreamChunk.ToolCallStart("c1", "search"))
                emit(StreamChunk.Finish("stop"))
            },
            state,
        ).streamText(setting, emptyList(), params).toList()

        val watch = state.watch(conv)
        assertEquals(0, watch.text.length)
        assertEquals(0, watch.reasoning.length)
        assertEquals(2, watch.chunks)
    }

    // ------------------------------------------------------------------
    // 截断识别
    // ------------------------------------------------------------------

    @Test
    fun `truncating finish reason is recorded`() = runTest {
        val state = ArmorState()

        armor(flow { emit(StreamChunk.Finish("content_filter")) }, state)
            .streamText(setting, emptyList(), params).toList()

        val watch = state.watch(conv)
        assertTrue("content_filter 必须被认成截断", watch.truncated)
        assertEquals("content_filter", watch.rawFinishReason)
        assertEquals("截断信号要写进状态，供下一轮续写", "content_filter", state.peekTruncation(conv))
    }

    @Test
    fun `finish reason matching is case insensitive`() = runTest {
        val state = ArmorState()

        armor(flow { emit(StreamChunk.Finish("REFUSAL")) }, state)
            .streamText(setting, emptyList(), params).toList()

        assertTrue("各家大小写不统一，必须lower后才比", state.watch(conv).truncated)
        assertEquals("REFUSAL", state.watch(conv).rawFinishReason)
    }

    @Test
    fun `normal finish reason is not recorded`() = runTest {
        val state = ArmorState()

        armor(flow { emit(StreamChunk.Finish("stop")) }, state)
            .streamText(setting, emptyList(), params).toList()

        val watch = state.watch(conv)
        assertFalse("正常结束不能被当成截断", watch.truncated)
        assertNull("stop 不该产生续写信号", state.peekTruncation(conv))
    }

    @Test
    fun `finish without reason is not recorded`() = runTest {
        val state = ArmorState()

        armor(flow { emit(StreamChunk.Finish(null)) }, state)
            .streamText(setting, emptyList(), params).toList()

        assertFalse(state.watch(conv).truncated)
        assertNull(state.peekTruncation(conv))
    }

    // ------------------------------------------------------------------
    // 异常必须上抛，不许被监看吞掉
    // ------------------------------------------------------------------

    @Test
    fun `delegate failure propagates to the caller`() = runTest {
        val state = ArmorState()
        val boom = IllegalStateException("upstream reset")
        val provider = armor(flow { throw boom }, state)

        val caught = runCatching {
            provider.streamText(setting, emptyList(), params).toList()
        }.exceptionOrNull()

        assertSame("真断流必须让调用方看到，不能被吞成正常结束", boom, caught)
    }

    @Test
    fun `stream starts from a clean watch on every collection`() = runTest {
        val state = ArmorState()
        state.watch(conv).appendText("上一次收集留下的脏数据")

        armor(flow { emit(StreamChunk.TextDelta("t1", "新的一轮")) }, state)
            .streamText(setting, emptyList(), params).toList()

        assertEquals("每轮开始必须重置，脏数据不能混进来", "新的一轮", state.watch(conv).text.toString())
    }
}

/**
 * 只吐固定 chunk 的最小 Provider 实现。
 *
 * 上游 `Provider<ProviderSetting>` 的其他方法（embedding / image / quota）都有默认实现，
 * 这里只覆盖纯抽象的那三个。
 */
private class ChunkProvider(
    private val chunks: Flow<StreamChunk>,
) : Provider<ProviderSetting> {

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = TextGenerationResult(
        id = "result",
        model = params.model.modelId,
        message = UIMessage.assistant(""),
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = chunks

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("not used in ArmorProviderTest")
}
