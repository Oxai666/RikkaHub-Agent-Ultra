package me.rerere.rikkahub.data.plan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 计划存储。
 *
 * 这一版是**进程内的**：App 重启就没了。之所以先不做 Room，
 * 是因为计划的生命周期本来就只到这次任务结束，持久化收益很低，
 * 而一个 schema 变更就要走 Migration，代价不成比例。
 *
 * 真正需要清理的是「进程被杀时卡在 in_progress 的步骤」——
 * 重启后会谎称还在执行第 3 步。用 [resetInProgress] 兜这个。
 */
object PlanStore {

    private val plans = ConcurrentHashMap<String, PlanSnapshot>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<PlanSnapshot>>()

    private fun flowOf(conversationId: String): MutableStateFlow<PlanSnapshot> =
        flows.getOrPut(conversationId) { MutableStateFlow(plans[conversationId] ?: PlanSnapshot()) }

    fun observe(conversationId: String): StateFlow<PlanSnapshot> = flowOf(conversationId).asStateFlow()

    fun latest(conversationId: String): PlanSnapshot = plans[conversationId] ?: PlanSnapshot()

    fun set(conversationId: String, snapshot: PlanSnapshot) {
        plans[conversationId] = snapshot
        flowOf(conversationId).value = snapshot
    }

    fun clear(conversationId: String) {
        plans.remove(conversationId)
        flowOf(conversationId).value = PlanSnapshot()
    }

    /**
     * 把所有卡在 in_progress 的步骤退回 pending。
     *
     * 进程重启后调用。不调的话界面会一直显示「第 3 步执行中」，
     * 而实际上根本没人在跑。
     */
    fun resetInProgress(conversationId: String) {
        val current = plans[conversationId] ?: return
        if (current.steps.none { it.status == PlanStepStatus.IN_PROGRESS }) return
        val fixed = current.copy(
            steps = current.steps.map {
                if (it.status == PlanStepStatus.IN_PROGRESS) it.copy(status = PlanStepStatus.PENDING) else it
            },
        )
        set(conversationId, fixed)
    }

    /** 全局扫一遍，App 启动时调一次。 */
    fun resetAllInProgress() {
        plans.keys.toList().forEach(::resetInProgress)
    }
}
