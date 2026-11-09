package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
/**
 * 参考计数归零后多久回收会话。
 *
 * 原来 5 秒——太短了。工具执行、审批等待、队列里还有消息时，
 * refCount 可能刚好是 0（用户切到别的页面去了），5 秒一到就被回收，
 * 正在进行的流跟着断掉。用户看到的就是「用久了、调工具时突然断开」。
 *
 * 60 秒：足够覆盖任何短暂的空闲窗口，又不至于让内存里的会话堆积。
 */
private const val IDLE_TIMEOUT_MS = 60_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    // 会话状态
    val state = MutableStateFlow(initial)
    val messageQueue = MessageQueue()

    /**
     * 上一轮是不是被打断的（用户喊停 / 出错 / 断联）。
     *
     * 「续写」按钮靠它决定显不显示：只有被打断的回合才谈得上"接着说"，
     * 正常结束的回合再续就是硬凑。
     *
     * 存在内存里，进程重启后清零——重启之后再续写意义不大，
     * 用户重新发一句更直接。
     */
    @Volatile
    var lastRunInterrupted: Boolean = false
        internal set

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            // 用 isInUse 而不是「refCount <= 0 && !isGenerating」：
            // 后者漏了「消息队列里还有待发消息」这种情况。
            // 续写刚入队、还没派发的那一瞬间正好满足前两个条件，
            // 会话会被当成空闲回收，队列里的东西跟着蒸发。
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
