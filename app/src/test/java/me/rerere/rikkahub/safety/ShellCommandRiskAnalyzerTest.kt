package me.rerere.rikkahub.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令风险分析的行为锁定。
 *
 * 重点是**不误伤**：只读命令即便碰工作区外的路径也不该被拦，
 * 沙箱里 `mkdir /workspace/xxx` 必须放行。
 */
class ShellCommandRiskAnalyzerTest {

    private val root = "/workspace"

    private fun tier(cmd: String) = ShellCommandRiskAnalyzer.analyze(cmd, root).tier

    // ── 只读：一律 A 档，路径在哪都不管 ──

    @Test
    fun `read only commands are tier A`() {
        listOf(
            "ls -la", "cat foo.txt", "grep -r TODO src/", "find . -name '*.kt'",
            "head -20 README.md", "wc -l main.kt", "git status", "id", "pwd",
        ).forEach { assertTrue("`$it` 应该判成只读", tier(it) == RiskTier.A_READ) }
    }

    @Test
    fun `reading files outside the workspace is still tier A`() {
        // 读 /etc/os-release 是合法操作，不该被当成越界写入
        val a = ShellCommandRiskAnalyzer.analyze("cat /etc/os-release", root)
        assertEquals(RiskTier.A_READ, a.tier)
        assertTrue("只读命令不该产生越界目标", a.outsideWorkspaceTargets.isEmpty())
    }

    @Test
    fun `compound read only command stays tier A`() {
        val a = ShellCommandRiskAnalyzer.analyze("uname -a; cat /etc/os-release; id", root)
        assertEquals(RiskTier.A_READ, a.tier)
        assertEquals(3, a.subcommands.size)
        assertTrue(a.outsideWorkspaceTargets.isEmpty())
    }

    @Test
    fun `probe commands used to identify the sandbox stay tier A`() {
        // 这几条是摸环境的常用组合，必须畅通
        assertEquals(RiskTier.A_READ, tier("pwd; ls -la; id; uname -m"))
        assertEquals(RiskTier.A_READ, tier("cat /proc/version"))
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

    // ── 可撤销：B 档，沙箱内正常工作都要放行 ──

    @Test
    fun `file mutations are tier B`() {
        listOf(
            "cp a.txt b.txt", "mv a.txt b.txt", "tee out.log",
            "sed -i 's/a/b/' file.txt", "chmod 755 run.sh",
            "mkdir -p build", "touch new.txt",
        ).forEach { assertEquals("`$it` 应该判成可撤销", RiskTier.B_REVERSIBLE, tier(it)) }
    }

    @Test
    fun `mkdir inside workspace is allowed`() {
        val a = ShellCommandRiskAnalyzer.analyze("mkdir -p /workspace/logs /workspace/notes", root)
        assertEquals(RiskTier.B_REVERSIBLE, a.tier)
        assertTrue("工作区内的路径不该判越界", a.outsideWorkspaceTargets.isEmpty())
    }

    @Test
    fun `touch inside workspace is allowed`() {
        val a = ShellCommandRiskAnalyzer.analyze("touch /workspace/t.txt", root)
        assertEquals(RiskTier.B_REVERSIBLE, a.tier)
        assertTrue(a.outsideWorkspaceTargets.isEmpty())
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
    fun `path outside workspace is detected`() {
        assertTrue(ShellCommandRiskAnalyzer.isOutside("/data/local/tmp/x", root))
        assertTrue(ShellCommandRiskAnalyzer.isOutside("/system/bin/sh", root))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("/workspace/src/a.kt", root))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("src/a.kt", root))
    }

    @Test
    fun `null workspace root disables the outside check`() {
        // 不知道工作区在哪就不判，交给审批——
        // 之前这里返回 true（全算越界），连 ls /workspace 都被拒
        assertFalse(ShellCommandRiskAnalyzer.isOutside("/workspace/proj/a.kt", null))
        assertFalse(ShellCommandRiskAnalyzer.isOutside("a.kt", null))
    }

    @Test
    fun `only system critical roots are always outside`() {
        listOf("/", "/system/x", "/data/adb")
            .forEach { assertTrue("`$it` 应该算越界", ShellCommandRiskAnalyzer.isOutside(it, root)) }
        // /proc /sys /dev 读是正常的，不该拦
        listOf("/proc/1", "/sys/kernel", "/dev/block/sda")
            .forEach { assertFalse("`$it` 不该判越界", ShellCommandRiskAnalyzer.isOutside(it, root)) }
    }

    @Test
    fun `rm outside workspace escalates to tier C`() {
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
