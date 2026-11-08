package me.rerere.rikkahub.data.goal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 目标和它的运行时计数。
 *
 * 持久化用 JSON 文件而不是 Room：
 *  - 数据量极小（一个会话最多一个目标，几十字节）
 *  - 走 Room 就要加 entity、写 Migration、涨 DB 版本，为了这点数据不值
 *  - 文件坏了手工删掉就能恢复，DB 坏了要修
 *
 * 文件位置 `files/agent-state/goals.json`，跟工作区平级。
 */
object GoalStore {

    private const val TAG = "GoalStore"
    private const val DIR = "agent-state"
    private const val FILE = "goals.json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val goals = ConcurrentHashMap<String, Goal>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Goal?>>()

    /** 连续受阻计数，键是会话 id。不持久化——重启后从头数才是对的。 */
    private val blockedStreak = ConcurrentHashMap<String, Int>()

    /** 本会话已经自动续跑了几轮。同样不持久化。 */
    private val continuationCount = ConcurrentHashMap<String, Int>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    /** App 启动时调一次，把磁盘上的目标读回内存。 */
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
            val stored: Map<String, Goal> = json.decodeFromString(file.readText())
            goals.putAll(stored)
            stored.keys.forEach { key -> flowOf(key).value = goals[key] }
        }.onFailure {
            Log.w(TAG, "load failed, starting empty", it)
        }
    }

    private fun persist() {
        val file = storeFile() ?: return
        runCatching {
            file.writeText(json.encodeToString(HashMap(goals)))
        }.onFailure {
            Log.w(TAG, "persist failed", it)
        }
    }

    private fun flowOf(key: String): MutableStateFlow<Goal?> =
        flows.getOrPut(key) { MutableStateFlow(goals[key]) }

    fun observe(key: String): StateFlow<Goal?> = flowOf(key).asStateFlow()

    fun get(key: String): Goal? = goals[key]

    fun set(key: String, goal: Goal?) {
        if (goal == null) goals.remove(key) else goals[key] = goal
        flowOf(key).value = goal
        persist()
    }

    fun clear(key: String) {
        goals.remove(key)
        blockedStreak.remove(key)
        continuationCount.remove(key)
        flowOf(key).value = null
        persist()
    }

    /** 累加消耗。每轮跑完调一次。 */
    fun accumulate(key: String, tokens: Long, seconds: Long) {
        val g = goals[key] ?: return
        if (tokens <= 0 && seconds <= 0) return
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
