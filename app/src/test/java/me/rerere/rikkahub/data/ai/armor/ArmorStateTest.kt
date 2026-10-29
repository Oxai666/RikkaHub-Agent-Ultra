package me.rerere.rikkahub.data.ai.armor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [ArmorState] 的行为契约。
 *
 * 这一层管的是「跨轮次的状态」：纠正次数要不要留、截断信号多久过期、
 * 哪个会话归哪条。判定逻辑（[ArmorJudge]）出错只是判错一次，
 * 状态层出错会让上限失效、截断信号串会话、或者续写指令永不触发 ——
 * 而且这些错都不会崩，只会安静地做错事，所以必须锁住。
 */
class ArmorStateTest {

    private val conv = Uuid.random()

    // ------------------------------------------------------------------
    // 会话桶的生命周期
    // ------------------------------------------------------------------

    @Test
    fun `watch returns the same instance until reset`() {
        val state = ArmorState()

        val first = state.watch(conv)
        first.appendText("已写进去")

        assertSame("同一个会话必须拿回同一个桶", first, state.watch(conv))
        assertEquals("已写进去", state.watch(conv).text.toString())
    }

    @Test
    fun `reset watch keeps the correction counter but clears the rest`() {
        val state = ArmorState()
        repeat(5) { state.tryAcquireCorrection(conv) }
        state.watch(conv).apply {
            appendText("上一轮的正文")
            appendReasoning("上一轮的推理")
            chunks = 12
            verdict = ArmorVerdict.Refusal
        }
        val previous = state.watch(conv)

        val fresh = state.resetWatch(conv)

        assertNotSame("reset 必须换一个新的桶", previous, fresh)
        assertSame("新桶要顶替旧桶成为当前桶", fresh, state.watch(conv))
        assertEquals("纠正次数必须跨轮保留，否则 20 次上限失效", 5, fresh.corrections)
        assertEquals("正文必须清空", 0, fresh.text.length)
        assertEquals("推理链必须清空", 0, fresh.reasoning.length)
        assertEquals("chunk 计数必须归零", 0, fresh.chunks)
        assertEquals("判定必须回到 Clean", ArmorVerdict.Clean, fresh.verdict)
    }

    // ------------------------------------------------------------------
    // 纠正上限
    // ------------------------------------------------------------------

    @Test
    fun `correction is capped at twenty`() {
        val state = ArmorState()

        repeat(ArmorState.MAX_CORRECTIONS) { index ->
            assertTrue("第 ${index + 1} 次申请应当放行", state.tryAcquireCorrection(conv))
        }
        assertFalse("第 21 次必须拒绝，否则模型持续拒答会无限注入", state.tryAcquireCorrection(conv))
        assertEquals(ArmorState.MAX_CORRECTIONS, state.watch(conv).corrections)
    }

    @Test
    fun `clear drops the counter back to zero`() {
        val state = ArmorState()
        repeat(3) { state.tryAcquireCorrection(conv) }
        state.markTruncated(conv, "refusal")

        state.clear(conv)

        assertEquals("清空后必须重新拿到完整额度", 0, state.watch(conv).corrections)
        assertNull("清空后截断信号也必须消失", state.peekTruncation(conv))
    }

    @Test
    fun `two conversations do not share counters`() {
        val state = ArmorState()
        val other = Uuid.random()
        repeat(3) { state.tryAcquireCorrection(conv) }

        assertEquals(3, state.watch(conv).corrections)
        assertEquals("另一个会话不该被牵连", 0, state.watch(other).corrections)
        assertTrue(state.tryAcquireCorrection(other))
        assertEquals(3, state.watch(conv).corrections)
    }

    @Test
    fun `mark corrected clears hits and parks the verdict`() {
        val state = ArmorState()
        state.watch(conv).apply {
            verdict = ArmorVerdict.Refusal
            refusalHits = listOf("我不能")
            safetyHits = listOf("建议咨询专业人士")
        }

        state.markCorrected(conv)

        val watch = state.watch(conv)
        assertEquals(ArmorVerdict.Corrected, watch.verdict)
        assertTrue("命中清单必须清空，否则同一轮会重复触发注入", watch.refusalHits.isEmpty())
        assertTrue(watch.safetyHits.isEmpty())
    }

    // ------------------------------------------------------------------
    // 截断信号
    // ------------------------------------------------------------------

    @Test
    fun `truncation is consumed exactly once`() {
        val state = ArmorState()
        state.markTruncated(conv, "content_filter")

        assertEquals("content_filter", state.consumeTruncation(conv))
        assertNull("取走后再取必须为空，否则一次截断会触发多次续写", state.consumeTruncation(conv))
    }

    @Test
    fun `peek does not consume`() {
        val state = ArmorState()
        state.markTruncated(conv, "refusal")

        assertEquals("refusal", state.peekTruncation(conv))
        assertEquals("refusal", state.peekTruncation(conv))
        assertEquals("peek 之后 consume 仍应拿到", "refusal", state.consumeTruncation(conv))
    }

    @Test
    fun `truncation expires after the ttl`() {
        var now = 1_000_000L
        val state = ArmorState { now }
        state.markTruncated(conv, "content_filter")

        now += ArmorState.TRUNCATION_TTL_MS + 1

        assertNull("超过 120s 属于陈旧信号，不该触发续写", state.consumeTruncation(conv))
    }

    @Test
    fun `truncation is still valid exactly at the ttl boundary`() {
        var now = 1_000_000L
        val state = ArmorState { now }
        state.markTruncated(conv, "content_filter")

        now += ArmorState.TRUNCATION_TTL_MS

        assertEquals("边界那一刻仍算有效（<= 而非 <）", "content_filter", state.peekTruncation(conv))
        assertEquals("content_filter", state.consumeTruncation(conv))
    }

    // ------------------------------------------------------------------
    // 滚动窗口：正文留头，推理链留尾（方向相反，写反了不会报错）
    // ------------------------------------------------------------------

    @Test
    fun `text window keeps the head while reasoning window keeps the tail`() {
        val watch = ArmorWatch()

        watch.appendText("A".repeat(10_000) + "B".repeat(10_000))
        assertTrue("正文必须保留头部：判定只看开头 240 字", watch.text.startsWith("A"))
        assertFalse("正文不该保留尾部", watch.text.endsWith("B"))
        assertEquals(ArmorWatch.TEXT_KEEP, watch.text.length)

        watch.appendReasoning("X".repeat(5_000) + "Y".repeat(5_000))
        assertTrue("推理链必须保留尾部：拒答念头在末尾才冒出来", watch.reasoning.endsWith("Y"))
        assertFalse("推理链不该保留头部", watch.reasoning.startsWith("X"))
        assertEquals(ArmorWatch.REASONING_KEEP, watch.reasoning.length)
    }

    @Test
    fun `short inputs are not truncated by the rolling window`() {
        val watch = ArmorWatch()
        watch.appendText("短正文")
        watch.appendReasoning("短推理")

        assertEquals("短正文", watch.text.toString())
        assertEquals("短推理", watch.reasoning.toString())
    }
}
