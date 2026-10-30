package me.rerere.rikkahub.browser

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.browser.cdp.DebugPausedState
import me.rerere.rikkahub.browser.hook.HookHitRecord

/**
 * 浏览器事件总线（裁剪版，来自太墟 BrowserEventBus）。
 *
 * 除了广播给订阅方，还把 console / network / hookHits 各自压成定长 StateFlow，
 * 这样工具层（browser_console_list、browser_network_list、browser_hook_hits）
 * 可以直接读快照，不需要自己维护环形缓冲。
 */
class BrowserEventBus {

    private val _events = MutableSharedFlow<BrowserEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _network = MutableStateFlow<List<CapturedRequest>>(emptyList())
    val network: StateFlow<List<CapturedRequest>> = _network.asStateFlow()

    private val _hookHits = MutableStateFlow<List<HookHitRecord>>(emptyList())
    val hookHits: StateFlow<List<HookHitRecord>> = _hookHits.asStateFlow()

    /** 当前处于 paused 的 tab（key = tabId）。断点命中时写入，resume 时移除。 */
    private val _paused = MutableStateFlow<Map<String, DebugPausedState>>(emptyMap())
    val paused: StateFlow<Map<String, DebugPausedState>> = _paused.asStateFlow()

    fun publish(event: BrowserEvent) {
        _events.tryEmit(event)
        when (event) {
            is BrowserEvent.ConsoleLogged -> _console.update { current ->
                (current + ConsoleLine(event.tabId, event.level, event.message, event.at))
                    .takeLast(MAX_CONSOLE)
            }

            is BrowserEvent.NetworkCaptured -> _network.update { current ->
                (current + event.request).takeLast(MAX_NETWORK)
            }

            is BrowserEvent.HookHit -> _hookHits.update { current ->
                (current + event.hit).takeLast(MAX_HOOK_HITS)
            }

            is BrowserEvent.DebugPaused -> _paused.update { it + (event.tabId to event.state) }

            is BrowserEvent.DebugResumed -> _paused.update { it - event.tabId }
        }
    }

    fun clearConsole() = _console.update { emptyList() }

    fun clearNetwork() = _network.update { emptyList() }

    fun clearHookHits() = _hookHits.update { emptyList() }

    /** tab 关闭时清掉它的残留，避免列表越积越长。 */
    fun clearForTab(tabId: String) {
        _console.update { list -> list.filterNot { it.tabId == tabId } }
        _network.update { list -> list.filterNot { it.tabId == tabId } }
        _hookHits.update { list -> list.filterNot { it.tabId == tabId } }
    }

    private companion object {
        const val MAX_CONSOLE = 500
        const val MAX_NETWORK = 300
        const val MAX_HOOK_HITS = 300
    }
}
