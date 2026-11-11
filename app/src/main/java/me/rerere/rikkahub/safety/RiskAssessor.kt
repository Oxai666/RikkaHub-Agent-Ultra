package me.rerere.rikkahub.safety

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ToolAccess

/**
 * 综合风险评估。
 *
 * 把三样东西合成一个 [RiskAssessment]：shell 命令分析、路径策略、
 * 以及"这个工具本身是干什么的"。
 *
 * 顺序很重要：先按工具类型分流，再在各自的通道里加深。
 * 不分流的话，`set_volume` 和 `rm -rf` 会被当成同一类，
 * 要么全拦要么全放。
 */
object RiskAssessor {

    /** 会跑 shell 的工具。命令字符串藏在哪个字段里各家不一样，见 [commandOf]。 */
    private val SHELL_TOOLS = setOf(
        "termux_run_command", "termux_session_start", "termux_session_send",
        "shizuku_exec", "root_shell", "workspace_shell", "eval_javascript",
    )

    /** 直接写文件的工具。 */
    private val FILE_WRITE_TOOLS = setOf(
        "write_text_file", "write_binary_file", "delete_file", "move_file", "copy_file",
        "create_directory", "batch_delete", "batch_move", "batch_copy",
        "workspace_write_file", "workspace_create_folder", "workspace_edit_file",
        "zip_files", "unzip_file",
    )

    /** 改设备设置的。 */
    private val SETTING_TOOLS = setOf(
        "set_brightness", "set_volume", "set_torch", "set_wallpaper",
        "vibrate", "post_notification", "wake_screen",
    )

    /** 操作界面的。 */
    private val GUI_TOOLS = setOf(
        "tap", "long_press", "swipe", "scroll", "set_text", "click_node",
        "global_action", "launch_app", "launch_activity", "open_url", "open_file",
        "keyboard_type", "keyboard_press_key", "keyboard_delete", "keyboard_clear",
        "keyboard_set_cursor", "keyboard_select_range",
    )

    /**
     * 沙箱里工作区文件区的固定挂载点。
     *
     * `workspace_shell` / `workspace_*` 这些工具跑在 proot 里，
     * 它们看到的路径空间是**沙箱内部的**：App 的文件区挂在 `/workspace`，
     * 根是 rootfs。
     *
     * 所以判断越界必须用这个常量，不能用调用方传进来的 `workspaceCwd`——
     * 那是 App 侧的路径（形如 `/data/user/0/<pkg>/files/workspace/xxx`），
     * 在沙箱里根本不存在，拿它比什么都算越界。
     */
    private const val SANDBOX_WORKSPACE_ROOT = "/workspace"

    /**
     * 高权限 shell。跟普通 shell 的区别只有一条：
     * **它们能到达的地方远超工作区**，所以不走「越界预拒 + 快照备份」那套，
     * 改成「每一颗命令都要人亲自看、亲自勾」。
     */
    val PRIVILEGED_SHELL_TOOLS: Set<String> = setOf("root_shell", "shizuku_exec")

    /**
     * 完全放行的 shell：不审批、不备份、不预拒。
     *
     * Termux 那套跑在 App 私有环境的 proot 里，能碰到的东西本来就在工作区
     * 和它自己的 rootfs 之内——它没能力动 /system、动别的 App 数据，
     * 所以「越界」这个概念对它没有意义。而在它自己的盒子里删东西，
     * 重建 rootfs 就恢复了，快照反而是浪费。
     *
     * 注意这只是**安全层**放行。Hardline 仍然在它之前跑
     * （rm -rf / 这种照样拦），审批则由 ToolApprovalDefaults 决定——
     * 那边也已经把 termux_run_command 移出 ALWAYS_ASK 了。
     */
    val UNMANAGED_SHELL_TOOLS: Set<String> = setOf(
        "termux_run_command",
        "termux_session_start",
        "termux_session_send",
    )

    fun assess(
        toolName: String,
        args: JsonObject,
        workspaceRoot: String?,
    ): RiskAssessment {
        // 显式标了只读的，直接放行——不用再往下算
        if (ToolAccess.isReadOnly(toolName)) {
            return RiskAssessment.readOnly(toolName)
        }

        // 沙箱系工具的根固定，不看调用方传什么
        val effectiveRoot = if (toolName.startsWith("workspace_")) {
            SANDBOX_WORKSPACE_ROOT
        } else {
            workspaceRoot
        }

        return when {
            toolName in SHELL_TOOLS -> assessShell(toolName, args, effectiveRoot)
            toolName in FILE_WRITE_TOOLS -> assessFileWrite(toolName, args, effectiveRoot)
            toolName in SETTING_TOOLS -> assessSetting(toolName)
            toolName in GUI_TOOLS -> assessGui(toolName)
            else -> assessUnknown(toolName)
        }
    }

    // ── shell 通道 ──

    private fun assessShell(
        toolName: String,
        args: JsonObject,
        workspaceRoot: String?,
    ): RiskAssessment {
        // 完全放行的通道：不评估、不备份、不预拒
        if (toolName in UNMANAGED_SHELL_TOOLS) {
            return RiskAssessment(
                toolName = toolName,
                baseTier = RiskTier.A_READ,
                tier = RiskTier.A_READ,
                reasons = listOf("Termux 通道，按配置完全放行"),
                reversible = true,
                dryRunPlan = DryRunPlan.NONE,
                snapshotPlan = SnapshotPlan.NONE,
                requiresApproval = false,
            )
        }

        val command = commandOf(args)
        if (command.isNullOrBlank()) {
            return RiskAssessment(
                toolName = toolName,
                baseTier = RiskTier.B_REVERSIBLE,
                tier = RiskTier.B_REVERSIBLE,
                reasons = listOf("拿不到命令内容，无法评估"),
                reversible = false,
                dryRunPlan = DryRunPlan(
                    kind = DryRunKind.SHELL_COMMAND,
                    summary = "命令内容为空或无法解析",
                ),
                snapshotPlan = SnapshotPlan.NONE,
                requiresApproval = true,
            )
        }

        val analysis = ShellCommandRiskAnalyzer.analyze(command, workspaceRoot)

        // ── 高权限 shell：能跑，但不备份、不预拒，也不许绕过审批 ──
        //
        // root_shell（su）和 shizuku_exec（shell UID）走同一套规矩。
        // shizuku 权限比 root 低一档，但「能读别的 App 数据、能改系统设置、
        // 能 pm/am 装卸载」这几件事已经够越过「工作区」这个概念了，
        // 按工作区边界预拒同样是把工具用废。
        //
        // 为什么单独处理：
        //  1. 快照对它没意义。快照的路径映射是「沙箱 /workspace/x →
        //     filesDir/workspaces/<id>/files/x」，这两类命令操作的是真机路径
        //     （/system、/data/data/…），映射过去一概不存在，拍了也是空目录。
        //     而且它们能改的东西本来也不是「复制一份就能回滚」的。
        //  2. 越界预拒对它是反的。用途就是动工作区外的东西——
        //     写 /system、读别的 App 私有数据。按越界一律拒等于把工具用废。
        //  3. 但必须是**每一条命令都亲自确认**，所以 requiresApproval 恒为 true，
        //     而且配合 ToolApprovalDefaults 里的 NO_ALWAYS_ALLOW（挡住 ∞ 按钮和
        //     旧授权）+ ChatService 里 isToolAutoApproved 的例外（挡住 YOLO
        //     和 headless）。三道锁缺一不可，光靠 ALWAYS_ASK 只能保证「第一次会问」。
        if (toolName in PRIVILEGED_SHELL_TOOLS) {
            return RiskAssessment(
                toolName = toolName,
                baseTier = RiskTier.C_IRREVERSIBLE,
                tier = RiskTier.C_IRREVERSIBLE,
                reasons = analysis.reasons + "高权限 shell，每次执行都需要你亲自确认",
                targets = analysis.targets,
                reversible = false,
                dryRunPlan = DryRunPlan(
                    kind = DryRunKind.SHELL_COMMAND,
                    summary = buildShellSummary(analysis),
                    commandText = command,
                    targetPaths = analysis.targets,
                    riskyFlags = analysis.riskyFlags,
                    details = analysis.subcommands.map { "${it.name} → ${it.tier.name}" },
                ),
                // 明确不备份：见上面第 1 条
                snapshotPlan = SnapshotPlan.NONE,
                requiresApproval = true,
                outsideWorkspaceTargets = analysis.outsideWorkspaceTargets.isNotEmpty(),
            )
        }

        // 工作区外 + 不可逆 = 直接拒，不给审批的机会
        if (analysis.outsideWorkspaceTargets.isNotEmpty() &&
            analysis.tier == RiskTier.C_IRREVERSIBLE
        ) {
            return RiskAssessment.block(
                toolName = toolName,
                code = SafetyBlockCodes.OUTSIDE_WORKSPACE,
                message = "目标在工作区外且不可恢复：" +
                    analysis.outsideWorkspaceTargets.joinToString(", "),
                tier = RiskTier.C_IRREVERSIBLE,
                reasons = analysis.reasons,
            )
        }

        val dryRun = DryRunPlan(
            kind = DryRunKind.SHELL_COMMAND,
            summary = buildShellSummary(analysis),
            commandText = command,
            targetPaths = analysis.targets,
            riskyFlags = analysis.riskyFlags,
            details = analysis.subcommands.map { "${it.name} → ${it.tier.name}" },
        )

        // ⚠ 只要有目标文件就拍，不再看风险等级。
        //
        // 原来只有 C_IRREVERSIBLE 才拍。问题是 `rm -f 单个文件` 被判成
        // B_REVERSIBLE（"可撤销"）—— 于是 .snapshots 里空空如也，
        // 用户删了文件想找回来，发现从来没有备份过。
        //
        // 实际上 rm 之后就没了，跟等级无关。B 档那些"可撤销"的假设
        // 只在文件工具的 dry-run 里有意义，shell 里删除就是真删。
        //
        // required 仍然只在 C 档为 true —— 那个字段现在只影响日志措辞，
        // "备份失败就拒绝"已经在上一轮砍掉了。
        val snapshot = if (analysis.targets.isNotEmpty()) {
            SnapshotPlan(
                scope = SnapshotScope.FILES,
                targets = analysis.targets.map { SnapshotTarget(path = it, exists = true) },
                required = analysis.tier == RiskTier.C_IRREVERSIBLE,
                captureSupported = true,
                noSnapshotAllowed = true,
            )
        } else {
            SnapshotPlan.NONE
        }

        return RiskAssessment(
            toolName = toolName,
            baseTier = analysis.tier,
            tier = analysis.tier,
            reasons = analysis.reasons,
            targets = analysis.targets,
            reversible = analysis.tier != RiskTier.C_IRREVERSIBLE,
            dryRunPlan = dryRun,
            snapshotPlan = snapshot,
            requiresApproval = analysis.tier.needsApproval,
            outsideWorkspaceTargets = analysis.outsideWorkspaceTargets.isNotEmpty(),
        )
    }

    private fun buildShellSummary(a: ShellCommandRiskAnalyzer.Analysis): String = buildString {
        append("将执行 ${a.subcommands.size} 条子命令")
        if (a.targets.isNotEmpty()) {
            append("，涉及 ${a.targets.size} 个路径")
        }
        when (a.tier) {
            RiskTier.A_READ -> append("（只读）")
            RiskTier.B_REVERSIBLE -> append("（可撤销）")
            RiskTier.C_IRREVERSIBLE -> append("（不可撤销）")
        }
    }

    /** 命令字符串在不同工具里叫法不同。 */
    private fun commandOf(args: JsonObject): String? {
        for (key in listOf("command", "cmd", "script", "code", "input")) {
            args[key]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    // ── 文件写通道 ──

    private fun assessFileWrite(
        toolName: String,
        args: JsonObject,
        workspaceRoot: String?,
    ): RiskAssessment {
        val paths = pathsOf(args)
        val outside = paths.filter { ShellCommandRiskAnalyzer.isOutside(it, workspaceRoot) }
        val destructive = toolName.contains("delete") || toolName.contains("move") ||
            toolName == "batch_delete"

        if (outside.isNotEmpty()) {
            return RiskAssessment.block(
                toolName = toolName,
                code = SafetyBlockCodes.OUTSIDE_WORKSPACE,
                message = "目标在工作区外：${outside.joinToString(", ")}",
                reasons = listOf("写入目标包含工作区外路径"),
            )
        }

        val tier = if (destructive) RiskTier.B_REVERSIBLE else RiskTier.B_REVERSIBLE
        return RiskAssessment(
            toolName = toolName,
            baseTier = tier,
            tier = tier,
            reasons = listOf(if (destructive) "会删除/移动文件" else "会写入文件"),
            targets = paths,
            reversible = true,
            dryRunPlan = DryRunPlan(
                kind = if (destructive) DryRunKind.FILE_TREE_IMPACT else DryRunKind.FILE_WRITE_DIFF,
                summary = if (destructive) {
                    "将影响 ${paths.size} 个路径"
                } else {
                    "将写入 ${paths.size} 个路径"
                },
                targetPaths = paths,
            ),
            snapshotPlan = SnapshotPlan(
                scope = SnapshotScope.FILES,
                targets = paths.map { SnapshotTarget(path = it, exists = true) },
                required = destructive,
                captureSupported = paths.isNotEmpty(),
                noSnapshotAllowed = !destructive,
            ),
            requiresApproval = true,
        )
    }

    private fun pathsOf(args: JsonObject): List<String> {
        val out = mutableListOf<String>()
        for (key in listOf("path", "file", "target", "source", "destination", "directory", "paths", "files")) {
            val el = args[key] ?: continue
            val prim = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
            if (!prim.isNullOrBlank()) {
                out += prim
            } else {
                runCatching {
                    (el as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
                        item.jsonPrimitive.contentOrNull?.let { if (it.isNotBlank()) out += it }
                    }
                }
            }
        }
        return out.distinct()
    }

    // ── 设置通道 ──

    private fun assessSetting(toolName: String): RiskAssessment = RiskAssessment(
        toolName = toolName,
        baseTier = RiskTier.B_REVERSIBLE,
        tier = RiskTier.B_REVERSIBLE,
        reasons = listOf("会修改系统设置，可能影响其他应用/设备行为"),
        reversible = true,
        dryRunPlan = DryRunPlan(
            kind = DryRunKind.SETTING_CHANGE,
            summary = "将改变设备设置",
        ),
        snapshotPlan = SnapshotPlan(
            scope = SnapshotScope.SETTINGS,
            targets = emptyList(),
            required = false,
            captureSupported = false,
            noSnapshotAllowed = true,
        ),
        requiresApproval = true,
    )

    // ── 界面通道 ──

    private fun assessGui(toolName: String): RiskAssessment = RiskAssessment(
        toolName = toolName,
        baseTier = RiskTier.B_REVERSIBLE,
        tier = RiskTier.B_REVERSIBLE,
        reasons = listOf("会操作界面，结果取决于屏幕上当时的画面"),
        reversible = false,
        dryRunPlan = DryRunPlan(
            kind = DryRunKind.GUI_CONFIRMATION,
            summary = "将向界面注入操作",
        ),
        snapshotPlan = SnapshotPlan.NONE,
        requiresApproval = true,
    )

    // ── 兜底 ──

    private fun assessUnknown(toolName: String): RiskAssessment = RiskAssessment(
        toolName = toolName,
        baseTier = RiskTier.B_REVERSIBLE,
        tier = RiskTier.B_REVERSIBLE,
        reasons = listOf("未登记的工具，按可写处理"),
        reversible = false,
        dryRunPlan = DryRunPlan(
            kind = DryRunKind.NONE,
            summary = "未知工具，无法预演",
        ),
        snapshotPlan = SnapshotPlan.NONE,
        requiresApproval = true,
        unknownTool = true,
    )
}
