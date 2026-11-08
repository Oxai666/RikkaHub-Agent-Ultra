package me.rerere.rikkahub.data.plan

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 计划存储。
 *
 * 持久化用 JSON 文件（`files/agent-state/plans.json`），跟 [me.rerere.rikkahub.data.goal.GoalStore]
 * 一个思路：数据量小（最多 12 步），走 Room 要加 entity + Migration 不值当。
 *
 * 真正需要处理的是「进程被杀时卡在 in_progress 的步骤」——
 * 重启后会谎称还在执行第 3 步。用 [resetInProgress] 兜这个。
 */
object PlanStore {

    private const val TAG = "PlanStore"
    private const val DIR = "agent-state"
    private const val FILE = "plans.json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val plans = ConcurrentHashMap<String, PlanSnapshot>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<PlanSnapshot>>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        load()
        loaded = true
    }

    private fun storeFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, FILE)
    }

    private fun load() {
        val file = storeFile() ?: return
        if (!file.isFile) return
        runCatching {
            val stored: Map<String, PlanSnapshot> = json.decodeFromString(file.readText())
            plans.putAll(stored)
            stored.keys.forEach { key -> flowOf(key).value = plans[key] ?: PlanSnapshot() }
        }.onFailure {
            Log.w(TAG, "load failed, starting empty", it)
        }
    }

    private fun persist() {
        val file = storeFile() ?: return
        runCatching {
            file.writeText(json.encodeToString(HashMap(plans)))
        }.onFailure {
            Log.w(TAG, "persist failed", it)
        }
    }

    private fun flowOf(conversationId: String): MutableStateFlow<PlanSnapshot> =
        flows.getOrPut(conversationId) { MutableStateFlow(plans[conversationId] ?: PlanSnapshot()) }

    fun observe(conversationId: String): StateFlow<PlanSnapshot> = flowOf(conversationId).asStateFlow()

    fun latest(conversationId: String): PlanSnapshot = plans[conversationId] ?: PlanSnapshot()

    fun set(conversationId: String, snapshot: PlanSnapshot) {
        plans[conversationId] = snapshot
        flowOf(conversationId).value = snapshot
        persist()
    }

    fun clear(conversationId: String) {
        plans.remove(conversationId)
        flowOf(conversationId).value = PlanSnapshot()
        persist()
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
