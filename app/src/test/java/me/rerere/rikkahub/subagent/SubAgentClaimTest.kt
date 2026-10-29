package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * claim 核验的行为契约。
 *
 * 这一层存在的唯一目的就是"别信自报"：子智能体说 complete，父智能体要能凭文件/命令核一遍。
 * 所以测试重点全是降级路径 —— 没凭据、凭据缺失、凭据越界时必须降级，而不是照单全收。
 */
class SubAgentClaimTest {

    private val files = setOf("out/report.md", "src/main/Main.kt")

    private fun review(text: String, cwd: String? = "/workspace/proj") =
        reviewSubAgentClaim(text, cwd) { path -> path in files }

    @Test
    fun `no claim block means no review`() {
        assertNull(review("我做完了，报告在这里。"))
        assertNull(review(""))
    }

    @Test
    fun `complete claim with existing files stays complete`() {
        val text = """
            报告已写入。

            ```json
            {"status":"complete","summary":"写完报告","acceptance_criteria":[
              {"type":"files","paths":["out/report.md"],"claim":"报告文件"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)

        assertNotNull(result)
        assertEquals("complete", result!!.reportedStatus)
        assertEquals("complete", result.effectiveStatus)
        assertEquals(ClaimVerdict.VERIFIED, result.criteria.single().verdict)
    }

    @Test
    fun `missing file downgrades complete to partial`() {
        val text = """
            ```json
            {"status":"complete","summary":"写完了","acceptance_criteria":[
              {"type":"files","paths":["out/report.md","out/nope.md"],"claim":"两份产物"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!

        assertEquals("partial", result.effectiveStatus)
        assertEquals(ClaimVerdict.FAILED, result.criteria.single().verdict)
        assertTrue(result.report.contains("out/nope.md"))
    }

    @Test
    fun `absolute and parent-relative paths are rejected`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"files","paths":["/etc/passwd","../outside.md"],"claim":"越界"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!

        assertEquals(ClaimVerdict.FAILED, result.criteria.single().verdict)
        assertEquals("partial", result.effectiveStatus)
    }

    @Test
    fun `files criterion without paths fails`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[{"type":"files","paths":[],"claim":"空"}]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals(ClaimVerdict.FAILED, result.criteria.single().verdict)
    }

    @Test
    fun `verification command is never auto executed and has no backing`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"verification","command":"./gradlew test","claim":"测试通过"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!

        assertEquals(ClaimVerdict.UNVERIFIED, result.criteria.single().verdict)
        // 只有命令没有落盘凭据 → 不能算 complete
        assertEquals("partial", result.effectiveStatus)
        assertTrue(result.report.contains("./gradlew test"))
    }

    @Test
    fun `manual only claim is downgraded`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[{"type":"manual","claim":"请用户确认界面"}]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals(ClaimVerdict.MANUAL, result.criteria.single().verdict)
        assertEquals("partial", result.effectiveStatus)
    }

    @Test
    fun `claim without any criteria is downgraded`() {
        val text = """
            ```json
            {"status":"complete","summary":"我保证做完了"}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals("partial", result.effectiveStatus)
        assertTrue(result.report.contains("凭据不足"))
    }

    @Test
    fun `reported partial is kept as is`() {
        val text = """
            ```json
            {"status":"partial","acceptance_criteria":[]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals("partial", result.effectiveStatus)
        assertEquals("partial", result.reportedStatus)
    }

    @Test
    fun `reported failed is kept as is`() {
        val text = """
            ```json
            {"status":"failed","acceptance_criteria":[]}
            ```
        """.trimIndent()

        assertEquals("failed", review(text)!!.effectiveStatus)
    }

    @Test
    fun `no workspace means file evidence is unverified`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"files","paths":["out/report.md"],"claim":"报告"}
            ]}
            ```
        """.trimIndent()

        val result = review(text, cwd = null)!!

        assertEquals(ClaimVerdict.UNVERIFIED, result.criteria.single().verdict)
        // 没有工作区就无法背书落盘，complete 降级
        assertEquals("partial", result.effectiveStatus)
    }

    @Test
    fun `last json block wins`() {
        val text = """
            中间有一段普通 JSON：

            ```json
            {"status":"failed","acceptance_criteria":[]}
            ```

            最后是真正的 claim：

            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"files","paths":["src/main/Main.kt"],"claim":"代码"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals("complete", result.effectiveStatus)
        assertEquals("complete", result.reportedStatus)
    }

    @Test
    fun `unknown criterion type is treated as unverified`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[{"type":"vibes","claim":"感觉良好"}]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals(ClaimVerdict.UNVERIFIED, result.criteria.single().verdict)
        assertEquals("partial", result.effectiveStatus)
    }

    @Test
    fun `report mentions criterion counts`() {
        val text = """
            ```json
            {"status":"complete","summary":"交付报告","acceptance_criteria":[
              {"type":"files","paths":["out/report.md"],"claim":"报告"},
              {"type":"verification","command":"grep -c TODO out/report.md","claim":"无 TODO"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals("complete", result.effectiveStatus)
        assertTrue(result.report.startsWith("[claim] complete"))
        assertTrue(result.report.contains("1 verified"))
        assertTrue(result.report.contains("1 unverified"))
    }

    @Test
    fun `absolute workspace path is accepted as file evidence`() {
        // 真机上子智能体就是写 /workspace/out/report.md；归一化后要能对上工作区相对路径
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"files","paths":["/workspace/out/report.md"],"claim":"报告"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals(ClaimVerdict.VERIFIED, result.criteria.single().verdict)
        assertEquals("complete", result.effectiveStatus)
    }

    @Test
    fun `absolute path outside workspace is still rejected`() {
        val text = """
            ```json
            {"status":"complete","acceptance_criteria":[
              {"type":"files","paths":["/data/local/tmp/x.md"],"claim":"别处"}
            ]}
            ```
        """.trimIndent()

        val result = review(text)!!
        assertEquals(ClaimVerdict.FAILED, result.criteria.single().verdict)
        assertEquals("partial", result.effectiveStatus)
    }
}
