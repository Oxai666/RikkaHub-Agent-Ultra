package me.rerere.rikkahub.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令风险分析的行为锁定。
 *
 * 本地没有 Android 环境，CI 是唯一验证手段，所以纯逻辑必须自带测试。
 * 用例按「正常 / 危险 / 模糊」三类挑，每类都要有。
 */
class ShellCommandRiskAnalyzerTest {

    private val root = "/workspace/proj"

    private fun tier(cmd: String) = ShellCommandRiskAnalyzer.analyze(cmd, root).tier

    // ── 正常：只读命令一律 A 档 ──

    @Test
    fun `read only commands are tier A`() {
        listOf(
            "ls -la", "cat foo.txt", "grep -r TODO src/", "find . -name '*.kt'",
            "head -20 README.md", "wc -l main.kt", "git status", "id", "pwd",
        ).forEach { assertTrue("`$it` 应该判成只读", tier(it) == RiskTier.A_READ) }
    }

    // ── 危险：不可逆的一律 C 档 ──

    @Test
    fun `rm is tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("rm -rf ./build"))
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("rm foo.txt"))
    }

    @Test
    fun `dd is tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `shred and truncate are tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("shred -u secret.txt"))
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("truncate -s 0 data.log"))
    }

    @Test
    fun `find delete is tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("find . -name '*.tmp' -delete"))
    }

    @Test
    fun `find exec is tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("find . -name '*.kt' -exec rm {} \\;"))
    }

    @Test
    fun `sudo and shell wrappers are tier C`() {
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("sudo apt install vim"))
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("bash -c \"echo hi\""))
        assertEquals(RiskTier.C_IRREVERSIBLE, tier("sh -c 'ls'"))
    }

    // ── 可撤销：B 档 ──

    @Test
    fun `file mutations are tier B`() {
        listOf(
            "cp a.txt b.txt", "mv a.txt b.txt", "tee out.log",
            "sed -i 's/a/b/' file.txt", "chmod 755 run.sh",
            "mkdir -p build", "touch new.txt",
        ).forEach { assertEquals("`$it` 应该判成可撤销", RiskTier.B_REVERSIBLE, tier(it)) }
    }

    @Test
    fun `downloads are tier B`() {
        assertEquals(RiskTier.B_REVERSIBLE, tier("curl -o out.zip https://example.com/x.zip"))
        assertEquals(RiskTier.B_REVERSIBLE, tier("wget https://example.com/x.zip"))
    }

    // ── 复合命令：取最高档 ──

    @Test
    fun `chained command takes the worst tier`() {
        val a = ShellCommandRiskAnalyzer.analyze("ls && rm -rf ./build", root)
        assertEquals(RiskTier.C_IRREVERSIBLE, a.tier)
        assertEquals(2, a.subcommands.size)

        val b = ShellCommandRiskAnalyzer.analyze("cat a.txt | grep foo", root)
        assertEquals(RiskTier.A_READ, b.tier)
    }

    // ── 路径越界 ──

    @Test
    fun `absolute path outside workspace counts as outside`() {
        assertTrue(ShellCommandRiskAnalyzer.isOutside("/data/local/tmp/x", root))
        assertTrue(ShellCommandRiskAnalyzer.isOutside("/system/bin/sh", root))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("/workspace/proj/src/a.kt", root))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("src/a.kt", root))
    }

    @Test
    fun `dangerous system roots are always outside`() {
        listOf("/", "/system/x", "/proc/1", "/sys/kernel", "/dev/block/sda", "/data/adb")
            .forEach { assertTrue("`$it` 应该算越界", ShellCommandRiskAnalyzer.isOutside(it, root)) }
    }

    @Test
    fun `null workspace root makes every absolute path outside`() {
        assertTrue(ShellCommandRiskAnalyzer.isOutside("/workspace/proj/a.kt", null))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("a.kt", null))
    }

    @Test
    fun `rm with outside target escalates to tier C`() {
        val a = ShellCommandRiskAnalyzer.analyze("rm /data/local/tmp/x", root)
        assertEquals(RiskTier.C_IRREVERSIBLE, a.tier)
        assertTrue(a.outsideWorkspaceTargets.isNotEmpty())
    }

    // ── 未知命令：按可写处理 ──

    @Test
    fun `unknown command defaults to tier B`() {
        assertEquals(RiskTier.B_REVERSIBLE, tier("somebrandnewtool --flag"))
    }

    @Test
    fun `empty command is harmless`() {
        val a = ShellCommandRiskAnalyzer.analyze("   ", root)
        assertEquals(RiskTier.A_READ, a.tier)
        assertTrue(a.subcommands.isEmpty())
    }
}

class AuditRedactorTest {

    @Test
    fun `key value secrets are redacted`() {
        val out = AuditRedactor.redact("curl -H 'Authorization: Bearer abc123def456'")
        assertFalse(out.contains("abc123def456"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test
    fun `password assignments are redacted`() {
        val out = AuditRedactor.redact("mysql --password=hunter2 --user=root")
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun `private key blocks are redacted`() {
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA1234567890
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val out = AuditRedactor.redact(pem)
        assertFalse(out.contains("MIIEowIBAAKCAQEA"))
        assertTrue(out.contains("REDACTED_PRIVATE_KEY"))
    }

    @Test
    fun `long hex strings are redacted`() {
        val hash = "a".repeat(64)
        val out = AuditRedactor.redact("sha256=$hash")
        assertFalse(out.contains(hash))
    }

    @Test
    fun `ordinary text survives`() {
        val text = "ls -la /workspace/proj"
        assertEquals(text, AuditRedactor.redact(text))
    }
}
