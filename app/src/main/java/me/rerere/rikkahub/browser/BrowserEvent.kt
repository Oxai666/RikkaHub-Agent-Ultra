package me.rerere.rikkahub.browser

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.browser.cdp.DebugPausedState
import me.rerere.rikkahub.browser.hook.HookHitRecord

/**
 * 浏览器侧事件（裁剪版，来自太墟 runtime/browser/BrowserEvent.kt）。
 *
 * 只保留 Hook / 网络 / console 三类 —— 太墟还有 SnapshotUpdated 与调试事件，
 * 它们依赖 PageSnapshot / ToolImageRef 等未移植模型，等需要时再补。
 */
sealed interface BrowserEvent {
    val tabId: String
    val at: Long

    @Serializable
    data class ConsoleLogged(
        override val tabId: String,
        val level: String,
        val message: String,
        override val at: Long = System.currentTimeMillis(),
    ) : BrowserEvent

    @Serializable
    data class HookHit(
        override val tabId: String,
        val hit: HookHitRecord,
        override val at: Long = System.currentTimeMillis(),
    ) : BrowserEvent

    @Serializable
    data class DebugPaused(
        override val tabId: String,
        val state: DebugPausedState,
        override val at: Long = System.currentTimeMillis(),
    ) : BrowserEvent

    @Serializable
    data class DebugResumed(
        override val tabId: String,
        override val at: Long = System.currentTimeMillis(),
    ) : BrowserEvent

    @Serializable
    data class NetworkCaptured(
        override val tabId: String,
        val request: CapturedRequest,
        override val at: Long = System.currentTimeMillis(),
    ) : BrowserEvent
}

/** 单条捕获的网络请求。source: native(shouldInterceptRequest) / js(hook) / cdp(Fetch 域)。 */
@Serializable
data class CapturedRequest(
    val id: String,
    val tabId: String,
    val url: String,
    val method: String,
    val statusCode: Int = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val errorMessage: String = "",
    val source: String = "native",
    val initiator: String = "",
    val durationMs: Long = 0L,
    val requestSize: Long = 0L,
    val responseSize: Long = 0L,
    val hasRequestBody: Boolean = false,
    val hasResponseBody: Boolean = false,
    val ruleId: String = "",
    val actionTaken: String = "",
)

@Serializable
data class ConsoleLine(
    val tabId: String,
    val level: String,
    val message: String,
    val at: Long = System.currentTimeMillis(),
)
