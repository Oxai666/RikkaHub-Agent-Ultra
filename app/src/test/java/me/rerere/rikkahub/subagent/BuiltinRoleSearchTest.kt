package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `subagent_roles` 的检索本体。
 * 模型靠这个工具拿准确角色名，所以过滤/截断/计数三个语义都得对：
 * 计错会让它以为"这个部门没有角色"，截断错会让它拿着不存在的名字去派发。
 */
class BuiltinRoleSearchTest {

    private val departments = listOf(
        BuiltinDepartment(id = "engineering", name = "Engineering", sortOrder = 0),
        BuiltinDepartment(id = "security", name = "Security", sortOrder = 1),
    )

    private val agents = listOf(
        BuiltinAgent("code_reviewer", "Code Reviewer", "Reviews pull requests", "engineering", sortOrder = 0),
        BuiltinAgent("android_engineer", "Android Engineer", "Kotlin and Compose", "engineering", sortOrder = 1),
        BuiltinAgent("security_auditor", "Security Auditor", "Threat modeling", "security", sortOrder = 0),
        BuiltinAgent("pentester", "Penetration Tester", "Authorized pentest", "security", sortOrder = 1),
    )

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `no filter returns everything with department index`() {
        val obj = parse(searchBuiltinRoles(agents, departments))
        assertEquals("4", obj["matched"]?.jsonPrimitive?.content)
        assertEquals("4", obj["returned"]?.jsonPrimitive?.content)
        assertEquals(2, obj["departments"]?.jsonArray?.size)
    }

    @Test
    fun `department filter narrows scope and omits department index`() {
        val obj = parse(searchBuiltinRoles(agents, departments, department = "security"))
        assertEquals("security", obj["scope_department"]?.jsonPrimitive?.content)
        assertEquals("2", obj["matched"]?.jsonPrimitive?.content)
        assertEquals(null, obj["departments"])
        val names = obj["roles"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("Security Auditor", "Penetration Tester")))
    }

    @Test
    fun `query matches name description and id`() {
        val byName = parse(searchBuiltinRoles(agents, departments, query = "auditor"))
        assertEquals("1", byName["matched"]?.jsonPrimitive?.content)

        val byDescription = parse(searchBuiltinRoles(agents, departments, query = "compose"))
        assertEquals("1", byDescription["matched"]?.jsonPrimitive?.content)
        assertEquals(
            "Android Engineer",
            byDescription["roles"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `limit truncates returned but keeps matched count`() {
        val obj = parse(searchBuiltinRoles(agents, departments, limit = 1))
        assertEquals("4", obj["matched"]?.jsonPrimitive?.content)
        assertEquals("1", obj["returned"]?.jsonPrimitive?.content)
        assertEquals(1, obj["roles"]!!.jsonArray.size)
    }

    @Test
    fun `unknown department yields empty roles`() {
        val obj = parse(searchBuiltinRoles(agents, departments, department = "nope"))
        assertEquals("0", obj["matched"]?.jsonPrimitive?.content)
        assertEquals(0, obj["roles"]!!.jsonArray.size)
    }

    @Test
    fun `single character tokens are ignored`() {
        // "a b" 里的单字母不该命中所有描述
        val obj = parse(searchBuiltinRoles(agents, departments, query = "a b"))
        assertEquals("4", obj["matched"]?.jsonPrimitive?.content)
    }
}
