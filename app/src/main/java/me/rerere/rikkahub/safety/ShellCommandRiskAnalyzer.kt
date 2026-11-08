package me.rerere.rikkahub.safety

/**
 * shell 命令风险分析。
 *
 * 原实现是 2921 行反编译 Java，这里按**语义**重写成精简版：
 * 不追求覆盖所有命令，只保证「能拆开、能认出危险的那几个、
 * 能挑出目标路径」。
 *
 * 设计上刻意做成纯函数：输入命令字符串 + 工作区根目录，输出分析结果。
 * 不碰文件系统、不碰 Android API——这样能单独跑测试，
 * 而本地编译不了的环境里，测试是唯一的验证手段。
 *
 * **这一层不是对抗性防护**。它挡的是「模型手滑」「用户没看清」，
 * 不是「有人故意构造绕过」。真要绕，变量拼接、base64、heredoc
 * 全都能过去。真正的兜底是审批弹窗 + 快照。
 */
object ShellCommandRiskAnalyzer {

    /** 一条分析出的子命令。 */
    data class SubCommand(
        val raw: String,
        val name: String,
        val args: List<String>,
        val targets: List<String>,
        val tier: RiskTier,
        val reasons: List<String>,
    )

    /** 整体分析结果。 */
    data class Analysis(
        val subcommands: List<SubCommand>,
        val tier: RiskTier,
        val reasons: List<String>,
        val targets: List<String>,
        val riskyFlags: List<String>,
        val outsideWorkspaceTargets: List<String>,
    ) {
        val worstTier: RiskTier get() = tier
    }

    // ── 命令分级 ──

    /** 怎么改都会丢数据的，直接 C 档。 */
    private val IRREVERSIBLE = setOf(
        "rm", "shred", "truncate", "dd", "mkfs", "mkfs.ext4", "mkfs.f2fs",
        "fdisk", "parted", "sgdisk", "wipefs",
        "shutdown", "reboot", "halt", "poweroff",
    )

    /** 能改但能撤销的（有备份/有版本控制），B 档。 */
    private val REVERSIBLE = setOf(
        "mv", "cp", "tee", "ln", "touch", "mkdir", "rmdir",
        "sed", "awk", "patch", "install",
        "chmod", "chown", "chgrp",
        "git", "svn", "hg",
        "apt", "apt-get", "dpkg", "pip", "pip3", "npm", "yarn", "pnpm",
        "curl", "wget",
        "kill", "pkill", "killall", "pkill",
    )

    /** 纯读，A 档。 */
    private val READ_ONLY = setOf(
        "ls", "cat", "head", "tail", "less", "more", "wc", "grep", "egrep", "fgrep",
        "find", "file", "stat", "du", "df", "tree", "pwd", "echo", "printf",
        "which", "whereis", "type", "id", "whoami", "uname", "date", "env",
        "cksum", "md5sum", "sha1sum", "sha256sum", "strings", "xxd", "hexdump",
        "diff", "cmp", "sort", "uniq", "cut", "tr", "rev", "basename", "dirname",
        "readlink", "realpath", "test", "true", "false", "sleep",
    )

    /** 提权命令，本身不能静态确认要干什么。 */
    private val PRIVILEGE_ESCALATION = setOf("sudo", "su", "doas", "pkexec")

    /** 包一层脚本解释器：`bash -c "..."` 这种，内容看不到就只能当不可逆。 */
    private val SHELL_WRAPPERS = setOf("bash", "sh", "zsh", "dash", "ksh", "ash", "fish")

    /** 会改变设备/系统状态的命令。 */
    private val SETTINGS_COMMANDS = setOf(
        "setprop", "settings", "svc", "cmd", "wm", "am", "pm", "input",
    )

    /**
     * 分析一条 shell 命令。
     *
     * @param command 原始命令
     * @param workspaceRoot 工作区根目录，用来判断目标是否越界。传 null 表示不知道，
     *        此时任何绝对路径都算越界（保守）。
     */
    fun analyze(command: String, workspaceRoot: String? = null): Analysis {
        val raw = command.trim()
        if (raw.isEmpty()) {
            return Analysis(
                subcommands = emptyList(),
                tier = RiskTier.A_READ,
                reasons = emptyList(),
                targets = emptyList(),
                riskyFlags = emptyList(),
                outsideWorkspaceTargets = emptyList(),
            )
        }

        val parts = splitSubcommands(raw)
        val subs = parts.map { analyzeOne(it, workspaceRoot) }

        val worst = subs.maxByOrNull { tierRank(it.tier) }?.tier ?: RiskTier.A_READ
        val reasons = subs.flatMap { it.reasons }.distinct()
        val targets = subs.flatMap { it.targets }.distinct()
        val riskyFlags = subs.flatMap { sub ->
            sub.args.filter { it.startsWith("-") && it.length > 1 }
        }.distinct()

        // 越界判断只对**写操作**做。
        // 之前把只读命令的路径也算进来，导致 `cat /etc/os-release` 被当成
        // 「往工作区外写东西」，整条命令升到 C 档直接拒——这是错的。
        // 读一个文件不改变任何东西，读哪里都不该拦。
        val writeTargets = subs
            .filter { it.tier != RiskTier.A_READ }
            .flatMap { it.targets }
            .distinct()
        val outside = writeTargets.filter { isOutside(it, workspaceRoot) }

        return Analysis(
            subcommands = subs,
            tier = if (outside.isNotEmpty()) RiskTier.C_IRREVERSIBLE else worst,
            reasons = if (outside.isNotEmpty()) {
                reasons + "写入目标位于工作区外，默认拒绝"
            } else reasons,
            targets = targets,
            riskyFlags = riskyFlags,
            outsideWorkspaceTargets = outside,
        )
    }

    private fun tierRank(t: RiskTier): Int = when (t) {
        RiskTier.A_READ -> 0
        RiskTier.B_REVERSIBLE -> 1
        RiskTier.C_IRREVERSIBLE -> 2
    }

    /**
     * 按 `;` `&&` `||` `|` 换行拆分。
     *
     * 不做完整的 shell 语法解析（引号转义那套），因为：
     * 1. 这里的目的不是精确还原命令行，是找出危险片段；
     * 2. 真要精确，得上一个完整的 shell parser，不值得。
     */
    private fun splitSubcommands(raw: String): List<String> =
        raw.split(Regex("""(?:;|&&|\|\||\||\n)"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun analyzeOne(sub: String, workspaceRoot: String?): SubCommand {
        val tokens = tokenize(sub)
        if (tokens.isEmpty()) {
            return SubCommand(sub, "", emptyList(), emptyList(), RiskTier.A_READ, emptyList())
        }

        // 跳过前置的包装词
        var i = 0
        while (i < tokens.size && tokens[i] in setOf("nohup", "setsid", "exec", "time", "env")) i++
        // 跳过 VAR=value 形式的环境变量赋值
        while (i < tokens.size && tokens[i].matches(Regex("""^[A-Za-z_]\w*=.*"""))) i++
        if (i >= tokens.size) {
            return SubCommand(sub, "", emptyList(), emptyList(), RiskTier.A_READ, emptyList())
        }

        val name = tokens[i].substringAfterLast('/')
        val args = tokens.drop(i + 1)
        val reasons = mutableListOf<String>()
        var tier = RiskTier.A_READ

        when {
            name in PRIVILEGE_ESCALATION -> {
                tier = RiskTier.C_IRREVERSIBLE
                reasons += "会提权执行，风险无法静态确认"
            }

            name in SHELL_WRAPPERS && args.any { it == "-c" } -> {
                tier = RiskTier.C_IRREVERSIBLE
                reasons += "command 会执行子命令，无法仅按只读内置命令放行"
                reasons += "脚本内容无法静态确认"
            }

            name == "rm" -> {
                tier = RiskTier.C_IRREVERSIBLE
                reasons += "rm 会永久删除文件，默认不可撤销"
                if (args.none { it.startsWith("-") } && args.isEmpty()) {
                    reasons += "rm 缺少可解析的目标"
                }
            }

            name == "dd" -> {
                tier = RiskTier.C_IRREVERSIBLE
                reasons += "dd 可直接覆写块设备/文件，误用会造成不可恢复损坏"
            }

            name == "find" -> {
                if (args.contains("-delete")) {
                    tier = RiskTier.C_IRREVERSIBLE
                    reasons += "find -delete 会递归删除匹配文件"
                } else if (args.contains("-exec")) {
                    tier = RiskTier.C_IRREVERSIBLE
                    reasons += "find -exec 可执行任意命令，无法静态确认影响"
                } else {
                    tier = RiskTier.A_READ
                }
            }

            name == "sed" && args.any { it == "-i" || it.startsWith("-i") } -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "sed -i 会就地覆盖文件"
            }

            name == "mv" -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "mv 可能覆盖或移走原文件，源路径将不再存在"
            }

            name == "cp" -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "cp 可能覆盖目标文件"
            }

            name == "tee" -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "tee 会覆盖/追加写入目标文件"
            }

            name == "git" -> {
                // git 的只读子命令太多（status / log / diff / show），
                // 一律按 B 档会把正常用法也拦下来。
                val sub = args.firstOrNull { !it.startsWith("-") }
                when (sub) {
                    "reset", "checkout", "clean", "rebase", "commit", "push",
                    "merge", "pull", "stash", "cherry-pick", "revert" -> {
                        tier = RiskTier.B_REVERSIBLE
                        reasons += "会丢弃工作区修改"
                        reasons += "会修改工作区或版本历史"
                    }
                    "status", "log", "diff", "show", "branch", "remote", "config", "blame" -> {
                        tier = RiskTier.A_READ
                    }
                    else -> {
                        tier = RiskTier.B_REVERSIBLE
                        reasons += "会修改工作区或版本历史"
                    }
                }
            }

            name == "curl" || name == "wget" -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "下载命令默认会写入本地文件，目标需由干跑确认"
            }

            name in SETTINGS_COMMANDS -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "会修改系统/设备状态"
            }

            name in IRREVERSIBLE -> {
                tier = RiskTier.C_IRREVERSIBLE
                reasons += "属于系统级不可逆操作，默认拒绝"
            }

            name in REVERSIBLE -> {
                tier = RiskTier.B_REVERSIBLE
                reasons += "会改变文件系统"
            }

            name in READ_ONLY -> {
                tier = RiskTier.A_READ
            }

            else -> {
                // 不认识的一律按可写处理：宁可多问一次，也不要放过一个没见过的写命令
                tier = RiskTier.B_REVERSIBLE
                reasons += "位置命令，风险无法静态确认"
            }
        }

        val targets = extractTargets(name, args)
        // 只有写操作才关心目标在不在工作区外
        if (tier != RiskTier.A_READ && targets.any { isOutside(it, workspaceRoot) }) {
            reasons += "写入目标包含工作区外路径"
        }

        return SubCommand(sub, name, args, targets, tier, reasons.distinct())
    }

    /** 粗粒度分词：按空白拆，去掉引号。 */
    private fun tokenize(sub: String): List<String> =
        sub.split(Regex("""\s+"""))
            .map { it.trim('"', '\'') }
            .filter { it.isNotEmpty() }

    /**
     * 从参数里挑出可能的目标路径。
     *
     * 规则：不以 `-` 开头、看起来像路径（含 `/` 或 `.`）、
     * 或者紧跟在会写文件的命令后面。
     */
    private fun extractTargets(name: String, args: List<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                // dd 的 of= 参数
                a.startsWith("of=") -> out += a.removePrefix("of=")

                // 重定向：`>` `>>` 后面的那个才是目标
                a == ">" || a == ">>" -> args.getOrNull(i + 1)?.let { out += it }

                // URL 不是路径。`https://x/y` 里既有 `:` 又有 `//`，
                // 被当成写目标是之前误拦的直接原因之一。
                a.contains("://") -> Unit

                // 普通路径参数
                !a.startsWith("-") && (a.contains('/') || a.startsWith(".")) -> out += a

                // mv/cp/tee/rm 的非选项参数全当目标
                !a.startsWith("-") && name in setOf(
                    "rm", "mv", "cp", "tee", "truncate", "shred", "touch", "mkdir", "rmdir", "ln",
                ) -> out += a
            }
            i++
        }
        return out.distinct()
    }

    /**
     * 这个路径在不在工作区外。
     *
     * 这里的判定标准分两种场景，不能混：
     *
     * **沙箱内**（[workspaceRoot] 是 `/workspace`）：proot 本身就是隔离的，
     * 写坏 `/usr/local` 重建 rootfs 就行，没必要按真机的标准拦。
     * 只有真正会把设备搞死的路径才拦。
     *
     * **真机**（`root_shell` 这类，root 是 App 侧路径）：按工作区边界严格判。
     */
    fun isOutside(path: String, workspaceRoot: String?): Boolean {
        if (path.isBlank()) return false
        // 相对路径默认认为在工作区内
        if (!path.startsWith("/")) return false

        // URL 被 split 之后会剩一段 `//github.com/...`，那不是路径
        if (path.startsWith("//")) return false

        // /dev/null、/dev/stdout 这些是重定向的去处，不是真在写文件
        if (path.startsWith("/dev/")) {
            val name = path.removePrefix("/dev/").substringBefore('/')
            if (name in setOf(
                    "null", "zero", "stdout", "stderr", "stdin", "tty",
                    "random", "urandom", "full",
                )
            ) return false
        }

        // 这几个无论什么场景都算危险：动它们等于动设备
        if (path == "/" || path.startsWith("/system") || path.startsWith("/data/adb")) return true

        // 沙箱场景：除了上面那几条，写哪都放行
        if (workspaceRoot == null || workspaceRoot.trimEnd('/') == "/workspace") return false

        val root = workspaceRoot.trimEnd('/')
        return !(path == root || path.startsWith("$root/"))
    }
}
