package me.rerere.rikkahub.data.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 宠物状态的信号中枢。
 *
 * 为什么要有这个东西：宠物该表达什么，取决于「这一轮在不在跑」「有没有卡等你点」
 * 「刚才那轮结没结束」——这些信号分散在 ChatPage、ChatService、输入框三处，
 * 而宠物是画在 ChatInput 里的。硬把它们串成一条参数链要穿过四层 Composable。
 *
 * 所以改成：**谁有信号谁上报，宠物这边订阅。**
 *
 * 注意这是**进程内单例**，不分会话。当前只有一个聊天页可见，够用；
 * 以后要做多会话同时跑，得改成按 conversationId 分桶。
 */
object PetSignal {

    private val _mood = MutableStateFlow(PetMood())
    val mood: StateFlow<PetMood> = _mood.asStateFlow()

    /**
     * 用户最后一次发消息的时刻。
     *
     * 「睡觉」的计时器基于**回消息**，不是**摸屏幕**——
     * 主人一边看屏幕一边想事情不代表他不想跟鱼鱼说话，
     * 但 60 秒没回话这只鱼就该打盹了。
     */
    @Volatile
    private var lastUserMessageAt: Long = System.currentTimeMillis()

    /** 用户发了消息。重置睡觉计时，并清掉「刚做完」的庆祝状态。 */
    fun onUserMessage() {
        lastUserMessageAt = System.currentTimeMillis()
        _mood.value = _mood.value.copy(justFinished = false, justFailed = false)
    }

    /** 一轮开始跑。 */
    fun onGenerationStart() {
        _mood.value = _mood.value.copy(running = true, justFinished = false, justFailed = false)
    }

    /** 一轮正常结束。 */
    fun onGenerationFinish() {
        _mood.value = _mood.value.copy(running = false, justFinished = true)
    }

    /** 一轮以错误结束。 */
    fun onGenerationError() {
        _mood.value = _mood.value.copy(running = false, justFailed = true)
    }

    /**
     * 有没有工具停在等审批。
     *
     * **只认真正弹给用户的审批**。YOLO 或无审核模式下工具自动通过、
     * 根命令被 PRIVILEGED_SHELL_TOOLS 放行，那些都不该让宠物举牌子——
     * 用户什么都没得点，举牌子等于骗他。
     */
    fun setAwaitingApproval(awaiting: Boolean) {
        if (_mood.value.awaitingApproval == awaiting) return
        _mood.value = _mood.value.copy(awaitingApproval = awaiting)
    }

    /** 是否已经该睡了：60 秒没回消息。 */
    fun shouldSleep(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastUserMessageAt >= SLEEP_AFTER_MS

    /** 距离该睡还有多久（毫秒）。宠物那边用它安排一次延时刷新。 */
    fun untilSleep(nowMs: Long = System.currentTimeMillis()): Long =
        (lastUserMessageAt + SLEEP_AFTER_MS - nowMs).coerceAtLeast(0L)

    private const val SLEEP_AFTER_MS = 60_000L
}
