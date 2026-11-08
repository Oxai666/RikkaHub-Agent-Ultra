package me.rerere.rikkahub.data.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一个会话的协作模式。
 *
 * - [DEFAULT] 正常干活：想调什么工具调什么工具，边做边更新检查表。
 * - [PLAN]    只规划不动手：工具表被掐成只读子集，最后吐一个 `<proposed_plan>` 块。
 */
@Serializable
enum class CollaborationMode {
    @SerialName("default")
    DEFAULT,

    @SerialName("plan")
    PLAN,
    ;

    val isPlan: Boolean get() = this == PLAN

    companion object {
        fun fromSerialName(value: String?): CollaborationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
