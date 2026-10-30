package me.rerere.rikkahub.browser.cdp

import android.os.Process
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
     * 本包名下所有进程的 pid。
     *
     * WebView 的 devtools socket 由持有 WebView 的那个进程创建；只要 pid 猜错，
     * connect 仍会成功（抽象 namespace 里同名 socket 存在时），但对面不答 HTTP，
     * 于是 GET /json 抛 EAGAIN。所以要把同包进程全试一遍。
     */
    private fun packagePids(): List<Int> {
        val fallback = listOf(Process.myPid())
        val ctx = runCatching {
            org.koin.java.KoinJavaComponent.getKoin().get<android.content.Context>()
        }.getOrNull() ?: return fallback
        return runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val pkg = ctx.packageName
            am.runningAppProcesses.orEmpty()
                .filter { it.pkgName == pkg || it.pkgName.startsWith("$pkg:") }
                .map { it.pid }
                .ifEmpty { fallback }
        }.getOrDefault(fallback)
    }

    /**
     * socket 发现：候选逐个**用 GET /json 验证**（不是只 connect —— 抽象 socket 谁都能连上，
     * 连到死 socket 会得到「connect 成功但没人应答」的假阳性），全部失败延迟 300ms 重试共 3 次。
     */
    private suspend fun discoverTransport(): LocalSocketCdpTransport {
        var lastError: Exception? = null
        var discovery = DevToolsSocketResolver.discover(packagePids())
        repeat(3) { attempt ->
            if (attempt > 0) discovery = DevToolsSocketResolver.discover(packagePids())
            for (name in discovery.candidates) {
                val probeError: Exception
                try {
                    val conn = LocalSocketCdpTransport(name).open()
                    val body = try {
                        HttpOverSocket.get(conn, "/json")
                    } finally {
                        conn.close()
                    }
                    if (body.contains("webSocketDebuggerUrl")) {
                        return LocalSocketCdpTransport(name)
                    }
                    probeError = IOException("$name: /json 无 webSocketDebuggerUrl（不是 devtools socket）")
                } catch (e: Exception) {
                    probeError = e
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
