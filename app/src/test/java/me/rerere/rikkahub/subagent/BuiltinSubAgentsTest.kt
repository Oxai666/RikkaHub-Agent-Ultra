package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置角色库的两块纯逻辑：frontmatter 剥离 + 逐级降级路由。
 *
 * 路由这块值得单独锁：它只在"用户没配 profile 或名字没命中"时才走，
 * 出错不会崩，只会安静地把任务派给一个不相关的角色 —— 而主智能体会
 * 拿那个角色的答案当真。降级顺序错了等于静默派错人。
 */
class BuiltinSubAgentsTest {

    private fun agent(
        id: String,
        name: String,
        dept: String = "engineering",
        description: String = "",
        sortOrder: Int = 0,
    ) = BuiltinAgent(
        id = id,
        name = name,
        description = description,
        departmentId = dept,
        promptPath = "agency_agents/agents/$dept/$id.md",
        sortOrder = sortOrder,
    )

    private val roster = listOf(
        agent("code_reviewer", "Code Reviewer", sortOrder = 0, description = "Reviews code like a mentor."),
        agent("backend_architect", "Backend Architect", sortOrder = 1, description = "API design and database schema."),
        agent("android_engineer", "Android Engineer", sortOrder = 2, description = "Kotlin and Jetpack Compose apps."),
        agent("security_auditor", "Security Auditor", dept = "security", sortOrder = 0, description = "Threat modeling and audit."),
        agent("penetration_tester", "Penetration Tester", dept = "security", sortOrder = 1, description = "Authorized penetration testing."),
    )

    // ------------------------------------------------------------------
    // frontmatter
    // ------------------------------------------------------------------

    @Test
    fun `frontmatter is stripped and body kept`() {
        val raw = """
            ---
            name: Code Reviewer
            emoji: eye
            ---

            # Code Reviewer Agent
            You are **Code Reviewer**.
        """.trimIndent()

        val body = stripFrontmatter(raw)

        assertTrue(body.startsWith("# Code Reviewer Agent"))
        assertTrue(body.contains("You are **Code Reviewer**."))
        assertTrue(!body.contains("emoji:"))
    }

    @Test
    fun `text without frontmatter is returned as is`() {
        assertEquals("# plain\ntext", stripFrontmatter("# plain\ntext"))
    }

    @Test
    fun `unterminated frontmatter does not eat the body`() {
        val raw = "---\nname: broken\n# body without closing fence"
        // 没有闭合 --- 时按"没有 frontmatter"处理，宁可多带几行也不要吞掉正文
        assertTrue(stripFrontmatter(raw).contains("# body without closing fence"))
    }

    // ------------------------------------------------------------------
    // 路由降级
    // ------------------------------------------------------------------

    @Test
    fun `exact name wins over keywords`() {
        val routing = routeBuiltinAgent(roster, "code reviewer")
        assertEquals("Code Reviewer", routing?.agent?.name)
        assertEquals(BuiltinRouteVia.EXACT_NAME, routing?.via)
    }

    @Test
    fun `department keyword match prefers the requested department`() {
        val routing = routeBuiltinAgent(roster, "audit penetration", departmentId = "security")
        assertEquals("Penetration Tester", routing?.agent?.name)
        assertEquals(BuiltinRouteVia.DEPT_KEYWORD, routing?.via)
    }

    @Test
    fun `keyword falls back to global search when department misses`() {
        val routing = routeBuiltinAgent(roster, "kotlin compose", departmentId = "security")
        assertEquals("Android Engineer", routing?.agent?.name)
        assertEquals(BuiltinRouteVia.GLOBAL_KEYWORD, routing?.via)
    }

    @Test
    fun `unknown keywords degrade to first role in the department`() {
        val routing = routeBuiltinAgent(roster, "zzzz qqqq", departmentId = "security")
        assertEquals("Security Auditor", routing?.agent?.name)
        assertEquals(BuiltinRouteVia.DEPT_FIRST, routing?.via)
    }

    @Test
    fun `unknown keywords without department degrade to first role overall`() {
        val routing = routeBuiltinAgent(roster, "zzzz qqqq", departmentId = null)
        assertEquals("Code Reviewer", routing?.agent?.name)
        assertEquals(BuiltinRouteVia.ANY_FIRST, routing?.via)
    }

    @Test
    fun `empty roster routes to nothing`() {
        assertNull(routeBuiltinAgent(emptyList(), "anything"))
    }

    @Test
    fun `blank query routes to nothing`() {
        assertNull(routeBuiltinAgent(roster, "   "))
    }

    @Test
    fun `single character tokens are ignored`() {
        // 单字母 token 会命中几乎所有描述（比如 "a"），必须丢掉，否则路由等于随机
        val routing = routeBuiltinAgent(roster, "a b c", departmentId = null)
        assertEquals(BuiltinRouteVia.ANY_FIRST, routing?.via)
    }
}
