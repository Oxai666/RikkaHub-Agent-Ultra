package me.rerere.rikkahub.data.goal

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 目标三件套：get_goal / create_goal / update_goal。
 *
 * 边界刻意画窄：
 *  - `update_goal` 只给模型 complete / blocked / paused 三个值。
 *    `active`（恢复）归用户点按钮，`usage_limited` 归系统看额度错误。
 *    模型自己能把目标「恢复」的话，它会无限续命下去。
 *  - `blocked` 要连续三次才算数，一次受阻就投降的不给。
 *  - `complete` 目前靠模型自证，这是这套机制最软的一环，
 *    真要严谨得在标完成时跑一遍验收命令，下一步再补。
 */
fun goalTools(conversationKey: String?): List<Tool> {
    val key = conversationKey ?: "__default__"
    return listOf(
        getGoalTool(key),
        createGoalTool(key),
        updateGoalTool(key),
    )
}

private const val BLOCKED_THRESHOLD = 3

private fun snapshotJson(goal: Goal?) = buildJsonObject {
    if (goal == null) {
        put("has_goal", false)
        put("note", "这个会话还没有目标")
        return@buildJsonObject
    }
    put("has_goal", true)
    put("objective", goal.objective)
    put("status", goal.status.name.lowercase())
    put("status_label", goal.status.displayName())
    put("tokens_used", goal.tokensUsed)
    put("time_used_seconds", goal.timeUsedSeconds)
    put("is_live", goal.status.isLive)
    put("is_terminal", goal.status.isTerminal)
}

private fun getGoalTool(key: String): Tool = Tool(
    name = "get_goal",
    description = """
        Get the current goal for this thread, including status, token and elapsed-time usage.
        Returns has_goal=false when no goal is set.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
    execute = {
        listOf(UIMessagePart.Text(snapshotJson(GoalStore.get(key)).toString()))
    },
)

private fun createGoalTool(key: String): Tool = Tool(
    name = "create_goal",
    description = """
        Create a goal only when explicitly requested by the user or system/developer instructions;
        do not infer goals from ordinary tasks. Fails if an unfinished goal already exists;
        use update_goal only for status changes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("objective", buildJsonObject {
                    put("type", "string")
                    put("description", "What the user wants done, in their own words.")
                })
            },
            required = listOf("objective"),
        )
    },
    execute = { input ->
        val objective = input.jsonObject["objective"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (objective.isBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("ok", false); put("error", "objective is required") }.toString()
                )
            )
        }
        val existing = GoalStore.get(key)
        // 只拦「正在进行中」的。暂停 / 受阻 / 已完成都允许新建——
        // 拦着不放的话，用户做完一个目标就永远立不了下一个。
        if (existing != null && existing.status.isLive) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "GOAL_ALREADY_ACTIVE")
                        put(
                            "message",
                            "there is already a live goal; finish it (or pause it) before starting another"
                        )
                        put("objective", existing.objective)
                        put("status", existing.status.name.lowercase())
                    }.toString()
                )
            )
        }
        val goal = Goal(objective = objective)
        GoalStore.set(key, goal)
        GoalStore.clearBlocked(key)
        GoalStore.resetContinuation(key)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("objective", objective); put("status", "active")
        }.toString()))
    },
)

private fun updateGoalTool(key: String): Tool = Tool(
    name = "update_goal",
    description = """
        Change the goal status. Only three values are accepted:
        - paused: ONLY when the user explicitly asks to stop. Never on your own initiative.
        - complete: only when the objective is genuinely achieved with no work remaining.
          Do not mark complete just because you want to end the turn.
        - blocked: only when the same blocker has recurred for at least three consecutive
          goal turns. One failure is not enough; report the blocker in your reply instead.
        Do not attempt active / usage_limited / budget_limited — resume belongs to the user
        and the other two are set by the system.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("status", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("complete"))
                        add(kotlinx.serialization.json.JsonPrimitive("blocked"))
                        add(kotlinx.serialization.json.JsonPrimitive("paused"))
                    })
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional one-line reason, shown to the user.")
                })
            },
            required = listOf("status"),
        )
    },
    execute = { input ->
        val raw = input.jsonObject["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val goal = GoalStore.get(key)
        if (goal == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("ok", false); put("error", "NO_GOAL") }.toString()
                )
            )
        }
        val next = when (raw) {
            "complete" -> GoalStatus.COMPLETE
            "paused" -> GoalStatus.PAUSED
            "blocked" -> GoalStatus.BLOCKED
            else -> return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "STATUS_NOT_ASSIGNABLE")
                        put("message", "only complete / blocked / paused can be set by the model")
                    }.toString()
                )
            )
        }

        // blocked 要连续三次。不到就只记一次，把计数回给模型让它继续试。
        if (next == GoalStatus.BLOCKED) {
            val streak = GoalStore.recordBlocked(key)
            if (streak < BLOCKED_THRESHOLD) {
                return@Tool listOf(
                    UIMessagePart.Text(buildJsonObject {
                        put("ok", false)
                        put("error", "BLOCKED_TOO_EARLY")
                        put("streak", streak)
                        put("required", BLOCKED_THRESHOLD)
                        put(
                            "message",
                            "blocked needs $BLOCKED_THRESHOLD consecutive goal turns; " +
                                "this is attempt $streak. Keep trying, or explain the blocker in your reply."
                        )
                    }.toString())
                )
            }
        } else {
            GoalStore.clearBlocked(key)
        }

        val updated = goal.copy(status = next, updatedAtMs = System.currentTimeMillis())
        GoalStore.set(key, updated)
        listOf(UIMessagePart.Text(snapshotJson(updated).toString()))
    },
)
