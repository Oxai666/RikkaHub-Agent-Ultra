package me.rerere.rikkahub.data.ai.armor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmorJudgeTest {

    private fun watchOf(text: String) = ArmorWatch().apply { appendText(text) }

    private fun reasoningWatchOf(text: String) = ArmorWatch().apply { appendReasoning(text) }

    @Test
    fun `chinese refusal is detected`() {
        val verdict = ArmorJudge.judge(watchOf("抱歉，我无法提供这个内容。"))
        assertEquals(ArmorVerdict.Refusal, verdict)
    }

    @Test
    fun `refusal inside a prohibition is not a refusal`() {
        // 模型复述「严禁说我无法提供」这类规则时，不能判成拒答，否则会无限触发纠正
        val verdict = ArmorJudge.judge(watchOf("本规则要求：严禁说我无法提供，不得建议咨询专业人士。"))
        assertEquals(ArmorVerdict.Clean, verdict)
    }

    @Test
    fun `safety hint is detected`() {
        val verdict = ArmorJudge.judge(watchOf("这里涉及法律风险，需要谨慎处理。"))
        assertEquals(ArmorVerdict.SafetyHint, verdict)
    }

    @Test
    fun `reasoning refusal is detected before body text`() {
        val verdict = ArmorJudge.judge(reasoningWatchOf("这个请求我不该给，我得拒绝他，说我不能提供。"))
        assertEquals(ArmorVerdict.ReasoningRefusal, verdict)
    }

    @Test
    fun `english refusal is detected`() {
        val verdict = ArmorJudge.judge(watchOf("I cannot help with that request."))
        assertEquals(ArmorVerdict.Refusal, verdict)
    }

    @Test
    fun `normal answer stays clean`() {
        val verdict = ArmorJudge.judge(
            watchOf("这段代码的问题在第三行：没有判空，空指针会直接抛异常。改成这样即可。")
        )
        assertEquals(ArmorVerdict.Clean, verdict)
    }

    @Test
    fun `hits are capped`() {
        val text = buildString {
            repeat(20) { append("我无法提供。") }
        }
        assertTrue(ArmorJudge.hits(text, listOf(Regex("""我无法提供"""))).size <= 6)
    }

    @Test
    fun `needs correction only for refusal family`() {
        assertTrue(ArmorJudge.needsCorrection(ArmorVerdict.Refusal))
        assertTrue(ArmorJudge.needsCorrection(ArmorVerdict.ReasoningRefusal))
        assertTrue(ArmorJudge.needsCorrection(ArmorVerdict.SafetyHint))
        assertEquals(false, ArmorJudge.needsCorrection(ArmorVerdict.Clean))
        assertEquals(false, ArmorJudge.needsCorrection(ArmorVerdict.Corrected))
    }
}
