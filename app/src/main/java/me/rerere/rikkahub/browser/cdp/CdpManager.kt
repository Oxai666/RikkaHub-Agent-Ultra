package me.rerere.rikkahub.browser.cdp

import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.browser.BrowserEventBus
import me.rerere.rikkahub.browser.hook.HookRuleStore
import me.rerere.rikkahub.browser.hook.NetworkBodyStore

/**
 * CDP 生命周期总管：attach / detach / 规则联动。
 *
 * - app 级调试开关由启动偏好持有，attach 在主线程重试开启并等待完成；
 *   detach 不关闭全局开关，避免异步关闭任务与新 attach 竞态；
 * - socket 发现：pid 直连 → /proc/net/unix 扫描；失败重试 3×300ms，错误原文透传 agent；
 * - attach 数上限 4（防泄漏）；
 * - 断点在 detach 时快照（[persistedBreakpoints]），重 attach 经 setup 重放。
 */
class CdpManager(
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
) {
    sealed interface AttachResult {
        data class Attached(val targetUrl: String) : AttachResult
        data class AlreadyAttached(val targetUrl: String) : AttachResult
    }

    data class AttachedTabStatus(
        val tabId: String,
        val targetUrl: String,
        val paused: Boolean,
        val breakpoints: Int,
        val workers: Int,
    )

    data class CdpStatus(
        val attachedTabs: List<AttachedTabStatus>,
        val devToolsSocketActive: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachMutex = Mutex()

    private val connections = ConcurrentHashMap<String, CdpTabConnection>()
    private val persistedBreakpoints = ConcurrentHashMap<String, List<DebugBreakpoint>>()

    /** tab 数上限（与 WebViewTabPool 的 8 错开：CDP 会话更重）。 */
    private val maxAttach = 4

    fun connectionOf(tabId: String): CdpTabConnection? = connections[tabId]

    fun attachedTabIds(): List<String> = connections.keys.sorted()

    /**
     * attach tab：发现 socket → 匹配 target → 开 WS → 装配（断点重放 + auto-attach + Fetch）。
     * 失败抛异常（原文直达 agent），后续 attach 可重新尝试。
     */
    suspend fun attach(tabId: String): AttachResult = attachMutex.withLock {
        connections[tabId]?.let { return AttachResult.AlreadyAttached(it.target.url) }
        if (connections.size >= maxAttach) {
            throw IllegalStateException(
                "cdp attach limit reached ($maxAttach): browser.debug_detach other tabs first"
            )
        }
        WebViewDebugging.setEnabled(true)
        try {
            val transport = discoverTransport()
            val matcher = CdpTargetMatcher(transport, scope)
            val tabUrl = eventBus.urlOf(tabId)
            val match = matcher.matchPageTarget(tabId, tabUrl)
            val target = when (match) {
                is CdpTargetMatcher.TargetMatch.Matched -> match.target
                is CdpTargetMatcher.TargetMatch.NotFound -> throw IllegalStateException(
                    buildString {
                        append("cdp attach 失败（tab $tabId）: ").append(match.message)
                        if (match.candidates.isNotEmpty()) {
                            append("; 候选: ").append(
                                match.candidates.joinToString { "${it.type} ${it.url}" }
                            )
                        }
                    },
                )
            }
            val ws = openWebSocket(transport, target)
            val session = CdpSession(ws, scope)
            val conn = CdpTabConnection(tabId, target, session, store, bodyStore, eventBus)
            session.start(conn)
            connections[tabId] = conn
            try {
                conn.setup(persistedBreakpoints[tabId] ?: emptyList())
            } catch (e: Exception) {
                connections.remove(tabId)
                runCatching { conn.detach() }
                throw IllegalStateException("cdp setup 失败（tab $tabId）: ${e.message}", e)
            }
            AttachResult.Attached(target.url)
        } catch (e: Exception) {
            Log.e("RikkaCdp", "attach failed; ${WebViewDebugging.diagnostics()}", e)
            throw e
        }
    }

    /**
     * detach：tabId 省略 = 全部。返回 detach 数量；指定 tab 未 attach 时抛错。
     * detach 前先 resume（防页面冻结），断点快照供重 attach 重放。
     */
    suspend fun detach(tabId: String?): Int = attachMutex.withLock {
        if (tabId == null) {
            val ids = connections.keys.toList()
            ids.forEach { detachLocked(it) }
            ids.size
        } else {
            require(connections.containsKey(tabId)) { "tab $tabId 未 attach CDP（先 browser.debug_attach）" }
            detachLocked(tabId)
            1
        }
    }

    /** tab 关闭 / 崩溃时联动（可从任意线程调用；异步强制 detach）。 */
    fun onTabClosed(tabId: String) {
        persistedBreakpoints.remove(tabId)
        val conn = connections.remove(tabId) ?: return
        scope.launch {
            runCatching { conn.detach() }
        }
    }

    /** 规则变更（hook_install/remove/reset 后由引擎调用）：重载全部连接的 Fetch patterns。 */
    suspend fun onRulesChanged() {
        connections.values.forEach { runCatching { it.onRulesChanged() } }
    }

    suspend fun shutdown() = attachMutex.withLock {
        connections.values.forEach { runCatching { it.detach() } }
        connections.clear()
        persistedBreakpoints.clear()
        scope.cancel()
    }

    fun status(): CdpStatus = CdpStatus(
        attachedTabs = connections.map { (tabId, conn) ->
            AttachedTabStatus(
                tabId = tabId,
                targetUrl = conn.target.url,
                paused = conn.debug.paused.value != null,
                breakpoints = conn.debug.breakpoints().size,
                workers = conn.workerCount,
            )
        }.sortedBy { it.tabId },
        devToolsSocketActive = connections.isNotEmpty(),
    )

    // ===== 内部 =====

    private suspend fun detachLocked(tabId: String) {
        val conn = connections.remove(tabId) ?: return
        val breakpoints = runCatching { conn.detach() }.getOrDefault(emptyList())
        if (breakpoints.isNotEmpty()) persistedBreakpoints[tabId] = breakpoints
        else persistedBreakpoints.remove(tabId)
    }

    /**
     * socket 发现：候选逐个**用 GET /json 验证**（不是只 connect —— 抽象 socket 谁都能连上，
     * 连到死 socket 会得到「connect 成功但没人应答」的假阳性），全部失败延迟 300ms 重试共 3 次。
     */
    private suspend fun discoverTransport(): LocalSocketCdpTransport {
        var lastError: Exception? = null
        var discovery = DevToolsSocketResolver.discover(DevToolsSocketResolver.packagePids())
        repeat(3) { attempt ->
            if (attempt > 0) discovery = DevToolsSocketResolver.discover(DevToolsSocketResolver.packagePids())
            for (name in discovery.candidates) {
                val probeError: Exception = try {
                    val conn = LocalSocketCdpTransport(name).open()
                    val body = try {
                        HttpOverSocket.get(conn, "/json")
                    } finally {
                        conn.close()
                    }
                    // 认定标准：能当 JSON 解析就算 devtools。
                    // /json 返回 [] 是**正常**的（socket 已在监听，只是还没有 page target）；
                    // 早期把它判成「不是 devtools socket」，导致有效候选被丢掉，
                    // 表现成「grep 明明看得到 socket，attach 却说连不上」。
                    val head = body.trimStart()
                    if (head.startsWith("[") || head.startsWith("{")) {
                        return LocalSocketCdpTransport(name)
                    }
                    IOException("$name: /json 不像 devtools 响应（前 120 字: ${body.take(120)}）")
                } catch (e: Exception) {
                    e
                }
                lastError = probeError
            }
            if (attempt < 2) delay(300)
        }
        throw IOException(
            "webview devtools socket 连接失败（已重试 3 次；" +
                "候选=${discovery.candidates}; /proc/net/unix=${discovery.scanError ?: "readable"}; " +
                "${WebViewDebugging.diagnostics()}）: ${lastError?.message}",
            lastError,
        )
    }

    private fun openWebSocket(transport: CdpTransport, target: CdpTargetInfo): WsConnection {
        // webSocketDebuggerUrl 形如 ws://127.0.0.1/devtools/page/<id> → 取 path 部分
        val path = WsHandshake.targetPath(target.webSocketDebuggerUrl)
        val conn = transport.open()
        return try {
            WsHandshake.open(conn, path)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }
}
