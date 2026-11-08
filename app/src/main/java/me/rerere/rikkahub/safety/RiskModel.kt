package me.rerere.rikkahub.safety

/**
 * 风险分级。
 *
 * 只有三档，不是五档十档——分太细没人记得住，也没人会按不同的档位
 * 做不同的决策。三档刚好对应三种处理：
 *
 *  - [A_READ]         读操作，直接放行，连 dry-run 都不用出
 *  - [B_REVERSIBLE]   能撤销的操作，出 dry-run 摘要 + 快照计划，再问用户
 *  - [C_IRREVERSIBLE] 不可撤销，默认拒绝；工作区外的目标一律落这一档
 */
enum class RiskTier {
    A_READ,
    B_REVERSIBLE,
    C_IRREVERSIBLE,
    ;

    val needsApproval: Boolean get() = this != A_READ

    val isBlockedByDefault: Boolean get() = this == C_IRREVERSIBLE
}

/** dry-run 用哪种方式向用户说明"如果执行会发生什么"。 */
enum class DryRunKind {
    /** 不需要预演。 */
    NONE,

    /** 把命令原样展示 + 拆解出的子命令与目标。 */
    SHELL_COMMAND,

    /** 展示将要写入的路径和覆盖前的差异摘要。 */
    FILE_WRITE_DIFF,

    /** 展示将被影响的文件树（删除/移动这类）。 */
    FILE_TREE_IMPACT,

    /** 展示将要改变的设置项：旧值 → 新值。 */
    SETTING_CHANGE,

    /** 纯界面操作，只能让用户自己看着办。 */
    GUI_CONFIRMATION,
}

/** 快照范围。 */
enum class SnapshotScope {
    NONE,
    FILES,
    SETTINGS,
}

/** 单个快照对象。 */
data class SnapshotTarget(
    val path: String,
    /** 这个目标在快照时是否已经存在。不存在就不需要备份，但需要记下来。 */
    val exists: Boolean,
)

/**
 * 快照计划。
 *
 * [required] 为 true 表示「拍不了就别执行」——不可逆操作必须走这条路。
 * [captureSupported] 为 false 表示当前环境根本不支持快照（比如没有写权限），
 * 此时 [noSnapshotAllowed] 决定是放行还是拒绝。
 */
data class SnapshotPlan(
    val scope: SnapshotScope,
    val targets: List<SnapshotTarget>,
    val required: Boolean,
    val captureSupported: Boolean,
    val noSnapshotAllowed: Boolean,
) {
    companion object {
        val NONE = SnapshotPlan(
            scope = SnapshotScope.NONE,
            targets = emptyList(),
            required = false,
            captureSupported = false,
            noSnapshotAllowed = true,
        )
    }
}

/** dry-run 计划。 */
data class DryRunPlan(
    val kind: DryRunKind,
    val summary: String,
    val commandText: String? = null,
    val targetPaths: List<String> = emptyList(),
    val riskyFlags: List<String> = emptyList(),
    val details: List<String> = emptyList(),
) {
    companion object {
        val NONE = DryRunPlan(kind = DryRunKind.NONE, summary = "")
    }
}

/**
 * 一次风险评估的完整结果。
 *
 * 这是整条链路的产出物，也是审批界面唯一的输入——
 * 审批 UI 不该自己去猜「这条命令危不危险」。
 */
data class RiskAssessment(
    val toolName: String,
    val baseTier: RiskTier,
    val tier: RiskTier,
    val reasons: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val reversible: Boolean,
    val dryRunPlan: DryRunPlan = DryRunPlan.NONE,
    val snapshotPlan: SnapshotPlan = SnapshotPlan.NONE,
    val requiresApproval: Boolean,
    val blocked: Boolean = false,
    val blockCode: String? = null,
    val blockMessage: String? = null,
    val requiresDeviceUnlock: Boolean = false,
    val unknownTool: Boolean = false,
    val argsDigest: String = "",
    val outsideWorkspaceTargets: Boolean = false,
) {
    val canExecute: Boolean get() = !blocked

    companion object {
        /** 读操作：什么都不用问。 */
        fun readOnly(toolName: String): RiskAssessment = RiskAssessment(
            toolName = toolName,
            baseTier = RiskTier.A_READ,
            tier = RiskTier.A_READ,
            reversible = true,
            requiresApproval = false,
        )

        /** 直接拒绝。 */
        fun block(
            toolName: String,
            code: String,
            message: String,
            tier: RiskTier = RiskTier.C_IRREVERSIBLE,
            reasons: List<String> = emptyList(),
        ): RiskAssessment = RiskAssessment(
            toolName = toolName,
            baseTier = tier,
            tier = tier,
            reasons = reasons,
            reversible = false,
            requiresApproval = true,
            blocked = true,
            blockCode = code,
            blockMessage = message,
        )
    }
}

/** 拒绝码。 */
object SafetyBlockCodes {
    const val OUTSIDE_WORKSPACE = "SAFETY_OUTSIDE_WORKSPACE"
    const val IRREVERSIBLE_SHELL = "SAFETY_IRREVERSIBLE_SHELL"
    const val SNAPSHOT_REQUIRED = "SAFETY_SNAPSHOT_REQUIRED"
    const val HARDLINE = "SAFETY_HARDLINE"
    const val UNPARSEABLE_SHELL = "SAFETY_UNPARSEABLE_SHELL"
}
