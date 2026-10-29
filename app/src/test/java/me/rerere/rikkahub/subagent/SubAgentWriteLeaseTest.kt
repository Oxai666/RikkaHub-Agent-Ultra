package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写租约的判定。这是唯一挡在"子智能体手滑写到别人文件上"前面的东西，
 * 而且它只在越界时才有存在感 —— 判定写松了没人会发现，直到某次并发派发互相覆盖。
 */
class SubAgentWriteLeaseTest {

    @Test
    fun `empty lease is read only`() {
        val lease = SubAgentWriteLease(emptyList())
        assertTrue(lease.readOnly)
        assertFalse(lease.allows("/workspace/out/report.md"))
        assertFalse(lease.allows("out/report.md"))
    }

    @Test
    fun `star means whole workspace`() {
        val lease = SubAgentWriteLease(listOf("*"))
        assertTrue(lease.allows("/workspace/anything/at/all.txt"))
        assertTrue(lease.allows("deep/nested/file.kt"))
    }

    @Test
    fun `prefix inside the workspace is allowed`() {
        val lease = SubAgentWriteLease(listOf("out"))
        assertTrue(lease.allows("out/report.md"))
        assertTrue(lease.allows("/workspace/out/report.md"))
        assertTrue(lease.allows("/workspace/out"))
    }

    @Test
    fun `sibling directory sharing a prefix is not allowed`() {
        val lease = SubAgentWriteLease(listOf("out"))
        // 前缀必须是目录边界，out2 不是 out 的子目录
        assertFalse(lease.allows("out2/report.md"))
        assertFalse(lease.allows("src/main.kt"))
    }

    @Test
    fun `parent traversal cannot escape the lease`() {
        val lease = SubAgentWriteLease(listOf("out"))
        assertFalse(lease.allows("out/../secret.txt"))
        assertFalse(lease.allows("../outside/secret.txt"))
    }

    @Test
    fun `multiple prefixes`() {
        val lease = SubAgentWriteLease(listOf("out", "src/main"))
        assertTrue(lease.allows("out/a.md"))
        assertTrue(lease.allows("src/main/Foo.kt"))
        assertFalse(lease.allows("src/test/FooTest.kt"))
    }

    @Test
    fun `blank path is rejected`() {
        assertFalse(SubAgentWriteLease(listOf("out")).allows("   "))
    }

    @Test
    fun `normalize collapses dots and strips workspace root`() {
        assertEquals("a/b", normalizeWorkspacePath("/workspace/a/./b"))
        assertEquals("a/b", normalizeWorkspacePath("/workspace//a/b/"))
        assertEquals("", normalizeWorkspacePath("/workspace"))
        assertEquals("b", normalizeWorkspacePath("a/../../b"))
        assertEquals("out/x.txt", normalizeWorkspacePath("./out/x.txt"))
    }

    @Test
    fun `describe is human readable`() {
        assertTrue(SubAgentWriteLease(emptyList()).describe().contains("read-only"))
        assertTrue(SubAgentWriteLease(listOf("*")).describe().contains("full workspace"))
        assertTrue(SubAgentWriteLease(listOf("out")).describe().contains("out"))
    }

    @Test
    fun `ancestor directory of a leased file may be created`() {
        // 真机踩到过：租约只给 out/review.md，子智能体先 create_folder("out") 被拦，
        // 只能靠 write_file 的隐式建目录绕过去。祖先目录必须放行。
        val lease = SubAgentWriteLease(listOf("out/review.md"))
        assertTrue(lease.allows("/workspace/out"))
        assertTrue(lease.allows("out"))
    }

    @Test
    fun `sibling file under the ancestor directory is still denied`() {
        val lease = SubAgentWriteLease(listOf("out/review.md"))
        assertFalse(lease.allows("out/evil.md"))
        assertFalse(lease.allows("out2"))
    }
}
