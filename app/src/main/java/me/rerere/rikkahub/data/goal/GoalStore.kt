package me.rerere.rikkahub.data.goal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 目标和它的运行时计数。
 *
 * 这一版是进程内的，跟 [me.rerere.rikkahub.data.plan.PlanStore] 一个思路：
 * 先让功能跑通，持久化等真需要跨重启恢复再加。
 */
object GoalStore {

    private val goals = ConcurrentHashMap<String, Goal>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Goal?>>()

    /** 连续受阻计数，键是会话 id。 */
    private val blockedStreak = ConcurrentHashMap<String, Int>()

    /** 本会话已经自动续跑了几轮，用来卡上限。 */
    private val continuationCount = ConcurrentHashMap<String, Int>()

    private fun flowOf(key: String): MutableStateFlow<Goal?> =
        flows.getOrPut(key) { MutableStateFlow(goals[key]) }

    fun observe(key: String): StateFlow<Goal?> = flowOf(key).asStateFlow()

    fun get(key: String): Goal? = goals[key]

    fun set(key: String, goal: Goal?) {
        if (goal == null) goals.remove(key) else goals[key] = goal
        flowOf(key).value = goal
    }

    fun clear(key: String) {
        goals.remove(key)
        blockedStreak.remove(key)
        continuationCount.remove(key)
        flowOf(key).value = null
    }

    /** 累加消耗。每轮跑完调一次。 */
    fun accumulate(key: String, tokens: Long, seconds: Long) {
        val g = goals[key] ?: return
        set(key, g.copy(
            tokensUsed = g.tokensUsed + tokens,
            timeUsedSeconds = g.timeUsedSeconds + seconds,
            updatedAtMs = System.currentTimeMillis(),
        ))
    }

    /**
     * 记一次「这一轮还是没进展」。
     *
     * 返回累计次数。模型要连续三次才被允许标 blocked——
     * 一次就叫苦的，不给它这个出口。
     */
    fun recordBlocked(key: String): Int {
        val n = (blockedStreak[key] ?: 0) + 1
        blockedStreak[key] = n
        return n
    }

    fun blockedStreakOf(key: String): Int = blockedStreak[key] ?: 0

    /** 恢复正常：把受阻计数清零。 */
    fun clearBlocked(key: String) {
        blockedStreak.remove(key)
    }

    fun continuationCountOf(key: String): Int = continuationCount[key] ?: 0

    fun bumpContinuation(key: String): Int {
        val n = (continuationCount[key] ?: 0) + 1
        continuationCount[key] = n
        return n
    }

    fun resetContinuation(key: String) {
        continuationCount.remove(key)
    }
}
