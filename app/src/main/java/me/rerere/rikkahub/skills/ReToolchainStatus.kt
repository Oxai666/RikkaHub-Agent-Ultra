package me.rerere.rikkahub.skills

import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * 逆向工具链的状态检测。
 *
 * 为什么要这个东西：用户想知道「我的工具链装好了没」，
 * 现在的答案是「让 AI 跑十几条 --version 一条条看」——慢、费 token、
 * 而且 AI 可能只看了一半就说「都装好了」。
 *
 * 这里用**一条命令**把该查的查完，解析成结构化结果。
 */
object ReToolchainStatus {

    /** 工具链根目录。跟 install-rev.sh 里的 REV 保持一致。 */
    const val REV_HOME = "/workspace/rev"

    data class Status(
        val rootfsReady: Boolean,
        /** 工具名 → 版本行。null 表示 `command -v` 都没找到。 */
        val tools: Map<String, String?>,
        val jadxMcpJarBytes: Long,
        /** 有没有 aapt2 兼容层——没有的话 arm64 上回编必失败。 */
        val aapt2Compat: Boolean,
    ) {
        val jadxMcpReady: Boolean get() = jadxMcpJarBytes > 1_000_000

        /** 核心工具齐不齐。aapt2 单独算，因为只有回编才用得到。 */
        fun missingCore(): List<String> = tools.filterValues { it == null }.keys.toList()

        val coreReady: Boolean
            get() = rootfsReady && missingCore().isEmpty()
    }

    /**
     * 跑一条脚本把所有版本收上来。
     *
     * 用一条而不是十几条：每条 `workspace_shell` 都是一次进程启动，
     * 十几条下来要好几秒。
     */
    private const val PROBE_SCRIPT = """
        [ -x /bin/sh ] && echo "rootfs=OK" || echo "rootfs=MISSING"
        [ -f $REV_HOME/env.sh ] && . $REV_HOME/env.sh 2>/dev/null
        . $REV_HOME/env.sh 2>/dev/null || true
        for c in jadx apktool smali node java r2 aapt2-host; do
          if command -v "${'$'}c" >/dev/null 2>&1; then
            echo "${'$'}c=${'$'}("${'$'}c" --version 2>&1 | head -1 | tr -d '\n')"
          else
            echo "${'$'}c=MISSING"
          fi
        done
        echo "mcpiar=${'$'}(stat -c%s $REV_HOME/jar/jadx-headless-mcp.jar 2>/dev/null || echo 0)"
        [ -x $REV_HOME/bin/aapt2-compat ] && echo "aapt2compat=OK" || echo "aapt2compat=MISSING"
    """.trimIndent()

    suspend fun detect(
        workspaceRepository: WorkspaceRepository,
        workspaceId: String,
        timeoutMillis: Long = 30_000L,
    ): Status {
        val result = runCatching {
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = PROBE_SCRIPT,
                cwd = "",
                timeoutMillis = timeoutMillis,
            )
        }.getOrNull()

        val output = result?.stdout.orEmpty()
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()

        val rootfsReady = lines["rootfs"] == "OK"
        val tools = listOf("jadx", "apktool", "smali", "node", "java", "r2", "aapt2-host")
            .associateWith { name ->
                val v = lines[name]
                if (v.isNullOrBlank() || v == "MISSING") null else v
            }

        return Status(
            rootfsReady = rootfsReady,
            tools = tools,
            jadxMcpJarBytes = lines["mcpiar"]?.toLongOrNull() ?: 0L,
            aapt2Compat = lines["aapt2compat"] == "OK",
        )
    }

    /** 给人看的一行摘要。 */
    fun summarize(status: Status): String = buildString {
        if (!status.rootfsReady) {
            append("rootfs 没装好，先去工作区装。")
            return@buildString
        }
        val missing = status.missingCore()
        if (missing.isEmpty()) {
            append("核心工具齐全（jadx / apktool / smali / node / java / r2）。")
        } else {
            append("缺：${missing.joinToString(", ")}。")
        }
        append(
            if (status.jadxMcpReady) {
                " jadx-mcp 已下载（${status.jadxMcpJarBytes / 1024 / 1024}MB）。"
            } else {
                " jadx-mcp 没装。"
            }
        )
        if (!status.aapt2Compat) {
            append(" aapt2 兼容层缺失——arm64 上 apktool 回编会 SIGILL。")
        }
    }
}
