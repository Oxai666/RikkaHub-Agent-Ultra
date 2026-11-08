package me.rerere.rikkahub.data.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 一条计划步骤的状态。任意时刻最多一条 [IN_PROGRESS]。 */
@Serializable
enum class PlanStepStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,
    ;

    companion object {
        fun fromWire(value: String?): PlanStepStatus? = when (value?.lowercase()) {
            "pending" -> PENDING
            "in_progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            else -> null
        }
    }
}

/**
 * 一条计划步骤。
 *
 * [index] 从 1 开始，跟模型在 `update_plan` 里给的顺序一致。
 */
@Serializable
data class PlanStep(
    val index: Int,
    val step: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
)

/** 一次 `update_plan` 的结果快照。 */
@Serializable
data class PlanSnapshot(
    val steps: List<PlanStep> = emptyList(),
    val explanation: String? = null,
    val updatedAtMs: Long = 0L,
) {
    val isEmpty: Boolean get() = steps.isEmpty()

    fun completedCount(): Int = steps.count { it.status == PlanStepStatus.COMPLETED }

    /** 跑完的比例，给 UI 画进度条用。没有步骤时返回 0。 */
    fun progress(): Float =
        if (steps.isEmpty()) 0f else completedCount().toFloat() / steps.size
}
