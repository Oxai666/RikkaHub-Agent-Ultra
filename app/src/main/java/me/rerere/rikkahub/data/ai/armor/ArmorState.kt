package me.rerere.rikkahub.data.ai.armor

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** 一轮生成中监看得出的判定结果。 */
enum class ArmorVerdict {
    /** 没看出异常 */
    Clean,

    /** 正文里出现拒答句式 */
    Refusal,

    /** 推理链里出现拒答念头（正文还没写出来） */
    ReasoningRefusal,

    /** 出现安全/免责/劝退话术的苗头 */
    SafetyHint,

    /** 已经注入过纠正指令，等待下一轮重判 */
    Corrected,
}

/**
 * 单会话的监看桶。
 *
 * 与破甲的 streamWatch 对应：推理链、正文分别累积，判定结果供下一步的
 * [ArmorTransformer] 决定要不要注入纠正/续写指令。
 *
 * 只在 AgentLoop 的单线程上下文里读写，不做额外同步。
 */
class ArmorWatch {
    var reasoning: StringBuilder = StringBuilder()
    var text: StringBuilder = StringBuilder()
    var verdict: ArmorVerdict = ArmorVerdict.Clean
    var refusalHits: List<String> = emptyList()
    var safetyHits: List<String> = emptyList()

    /** 上游以 refusal / content_filter 之类的 reason 结束了流 */
    var truncated: Boolean = false
    var rawFinishReason: String? = null

    /** 本会话已注入纠正的次数 */
    var corrections: Int = 0

    var chunks: Int = 0

    fun appendReasoning(delta: String) {
        reasoning.append(delta)
        // 滚动窗口：只保留尾部，防止长推理链越判越慢
        if (reasoning.length > REASONING_KEEP * 2) {
            reasoning = StringBuilder(reasoning.takeLast(REASONING_KEEP))
        }
    }

    fun appendText(delta: String) {
        text.append(delta)
        if (text.length > TEXT_KEEP * 2) {
            text = StringBuilder(text.take(TEXT_KEEP))
        }
    }

    companion object {
        const val REASONING_KEEP = 4000
        const val TEXT_KEEP = 8000
    }
}

/** 一次截断记录：上游给的原始 reason + 发生时刻。 */
private data class ArmorTruncation(val reason: String, val at: Long)

/**
 * 跨轮次、跨步骤的会话状态。
 *
 * 用 conversationId 作键，对应 RikkaHub 的 `TextGenerationParams.sessionId`。
 * 多会话并发时各自独立，不共用「最近活跃」这种全局变量。
 */
class ArmorState(
    /**
     * 时间源。默认取系统时钟，测试时可注入假时钟来验证 TTL 边界，
     * 不必真的等 120 秒，也不用反射去挖私有字段。
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val watches = ConcurrentHashMap<Uuid, ArmorWatch>()
    private val truncations = ConcurrentHashMap<Uuid, ArmorTruncation>()

    fun watch(conversationId: Uuid): ArmorWatch =
        watches.getOrPut(conversationId) { ArmorWatch() }

    /** 每轮开始前重置监看，但保留 corrections 计数（跨轮上限） */
    fun resetWatch(conversationId: Uuid): ArmorWatch {
        val previous = watches[conversationId]
        val fresh = ArmorWatch().also { it.corrections = previous?.corrections ?: 0 }
        watches[conversationId] = fresh
        return fresh
    }

    fun markTruncated(conversationId: Uuid, reason: String) {
        truncations[conversationId] = ArmorTruncation(reason, clock())
    }

    /** 只看不消费（给状态展示用） */
    fun peekTruncation(conversationId: Uuid): String? {
        val record = truncations[conversationId] ?: return null
        return if (clock() - record.at <= TRUNCATION_TTL_MS) record.reason else null
    }

    /** 取走并清掉；超过 120s 视为陈旧，不触发续写 */
    fun consumeTruncation(conversationId: Uuid): String? {
        val record = truncations.remove(conversationId) ?: return null
        return if (clock() - record.at <= TRUNCATION_TTL_MS) record.reason else null
    }

    /**
     * 申请一次纠正注入名额。
     *
     * 每会话最多 [MAX_CORRECTIONS] 次：防「模型持续拒答 → 每步都注入 → 消息流被撑爆」。
     */
    fun tryAcquireCorrection(conversationId: Uuid): Boolean {
        val watch = watch(conversationId)
        if (watch.corrections >= MAX_CORRECTIONS) return false
        watch.corrections += 1
        return true
    }

    /** 注入完成后把判定拉回 Corrected，避免同一轮重复触发 */
    fun markCorrected(conversationId: Uuid) {
        val watch = watch(conversationId)
        watch.verdict = ArmorVerdict.Corrected
        watch.refusalHits = emptyList()
        watch.safetyHits = emptyList()
    }

    fun clear(conversationId: Uuid) {
        watches.remove(conversationId)
        truncations.remove(conversationId)
    }

    companion object {
        /** 截断信号有效期，对齐破甲 STOP_TRUNCATION_TTL_MS */
        const val TRUNCATION_TTL_MS = 120_000L

        /** 单会话纠正上限，对齐破甲 FORCE_ACCEPT_MAX_PER_SESSION */
        const val MAX_CORRECTIONS = 20
    }
}

/**
 * 全局单例。
 *
 * 没有走 DI 容器：这个状态本身无依赖、无生命周期需求，注册到
 * ChatRequestFactory 之外还可能漏掉压缩摘要等旁路调用。用 object 最省事。
 */
object ArmorRegistry {
    val state = ArmorState()
}
