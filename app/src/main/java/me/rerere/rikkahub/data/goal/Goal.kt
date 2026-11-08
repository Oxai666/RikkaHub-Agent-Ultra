package me.rerere.rikkahub.data.goal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 目标的六个状态。
 *
 * 模型只能把目标推向 [COMPLETE] / [BLOCKED] / [PAUSED] 三个；
 * [ACTIVE] 归用户（恢复），[USAGE_LIMITED] / [BUDGET_LIMITED] 归系统。
 * 这条边界画在工具 schema 里，不是靠提示词劝。
 */
@Serializable
enum class GoalStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("paused")
    PAUSED,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("usage_limited")
    USAGE_LIMITED,

    @SerialName("budget_limited")
    BUDGET_LIMITED,

    @SerialName("complete")
    COMPLETE,
    ;

    /** 还在推进中，续跑调度会考虑它。 */
    val isLive: Boolean get() = this == ACTIVE

    val isTerminal: Boolean get() = this == COMPLETE || this == BLOCKED

    fun displayName(): String = when (this) {
        ACTIVE -> "进行中"
        PAUSED -> "已暂停"
        BLOCKED -> "受阻"
        USAGE_LIMITED -> "额度用尽"
        BUDGET_LIMITED -> "预算用尽"
        COMPLETE -> "已完成"
    }
}

/**
 * 跨轮目标。
 *
 * 跟「计划」的区别：计划是这一轮怎么走，目标是跨多少轮、烧了多少、还差多少。
 */
@Serializable
data class Goal(
    val objective: String,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val tokensUsed: Long = 0L,
    val timeUsedSeconds: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    /** 连续受阻碍的轮数。到 3 才允许标 blocked。 */
    val blockedStreak: Int get() = 0

    fun isFinished(): Boolean = status.isTerminal
}
