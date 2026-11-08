package me.rerere.rikkahub.plan

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.plan.PlanParseResult
import me.rerere.rikkahub.data.plan.PlanStepStatus
import me.rerere.rikkahub.data.plan.PlanToolCatalog
import me.rerere.rikkahub.data.plan.ProposedPlanParser
import me.rerere.rikkahub.data.plan.parseAndValidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanToolValidateTest {

    private fun step(text: String, status: String) = buildJsonObject {
        put("step", text)
        put("status", status)
    }

    private fun plan(vararg steps: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("plan", buildJsonArray { steps.forEach { add(it) } })
    }

    @Test
    fun `valid plan passes`() {
        val r = parseAndValidate(
            plan(
                step("读一遍项目结构", "completed"),
                step("改 GenerationLoop", "in_progress"),
                step("跑测试", "pending"),
            )
        )
        assertTrue(r is PlanParseResult.Success)
        val steps = (r as PlanParseResult.Success).steps
        assertEquals(3, steps.size)
        assertEquals(PlanStepStatus.IN_PROGRESS, steps[1].status)
    }

    @Test
    fun `more than one in_progress is rejected`() {
        val r = parseAndValidate(
            plan(
                step("a", "in_progress"),
                step("b", "in_progress"),
            )
        )
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(
            PlanToolCatalog.ERR_TOO_MANY_IN_PROGRESS,
            (r as PlanParseResult.Failure).code
        )
    }

    @Test
    fun `thirteen steps is rejected`() {
        val r = parseAndValidate(
            plan(*Array(13) { step("step ${it + 1}", "pending") })
        )
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(PlanToolCatalog.ERR_TOO_MANY_STEPS, (r as PlanParseResult.Failure).code)
    }

    @Test
    fun `overlong step is rejected`() {
        val r = parseAndValidate(plan(step("字".repeat(121), "pending")))
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(PlanToolCatalog.ERR_STEP_TOO_LONG, (r as PlanParseResult.Failure).code)
    }

    @Test
    fun `blank step is rejected`() {
        val r = parseAndValidate(plan(step("   ", "pending")))
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(PlanToolCatalog.ERR_STEP_BLANK, (r as PlanParseResult.Failure).code)
    }

    @Test
    fun `bad status is rejected`() {
        val r = parseAndValidate(plan(step("a", "doing")))
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(PlanToolCatalog.ERR_BAD_STATUS, (r as PlanParseResult.Failure).code)
    }

    @Test
    fun `empty plan is rejected`() {
        val r = parseAndValidate(buildJsonObject { put("plan", buildJsonArray { }) })
        assertTrue(r is PlanParseResult.Failure)
        assertEquals(PlanToolCatalog.ERR_TOO_FEW_STEPS, (r as PlanParseResult.Failure).code)
    }
}

class ProposedPlanParserTest {

    @Test
    fun `parses a well formed block`() {
        val text = """
            前面说点什么。

            <proposed_plan>
            # 加个 Plan 模式

            ## Summary
            只在规划阶段只读。

            ## Test Plan
            跑单测。
            </proposed_plan>

            后面还可以聊。
        """.trimIndent()

        val p = ProposedPlanParser.parse(text)
        assertTrue(p != null)
        assertEquals("加个 Plan 模式", p!!.title)
        assertTrue(p.markdown.contains("## Summary"))
        assertTrue(p.markdown.startsWith("# 加个 Plan 模式"))
    }

    @Test
    fun `returns null when there is no block`() {
        assertNull(ProposedPlanParser.parse("就一段普通回复，没有计划。"))
    }

    @Test
    fun `returns null when tags are duplicated`() {
        val text = """
            <proposed_plan>
            # A
            </proposed_plan>

            <proposed_plan>
            # B
            </proposed_plan>
        """.trimIndent()
        assertNull(ProposedPlanParser.parse(text))
    }

    @Test
    fun `returns null when tags are not on their own line`() {
        val text = "前言 <proposed_plan> # A </proposed_plan> 后记"
        assertNull(ProposedPlanParser.parse(text))
    }

    @Test
    fun `returns null for empty body`() {
        val text = "<proposed_plan>\n\n</proposed_plan>"
        assertNull(ProposedPlanParser.parse(text))
    }
}
