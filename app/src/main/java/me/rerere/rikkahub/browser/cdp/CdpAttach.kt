package me.rerere.rikkahub.browser.cdp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一次性的 CDP 短连接：连 target → WS 握手 → 发一条命令 → 关掉。
 *
 * 为什么先做短连接而不是长效会话：
 *  - 短连接没有生命周期问题（不用管 detach、断线重连、多标签互斥）；
 *  - 它验证的恰好是最难的两段 —— `WsHandshake`（手写 WebSocket 握手）
 *    与 `CdpSession`（JSON-RPC 收发）。这两段通了，长效会话只是把它们常驻化。
 *
 * `CdpProbe` 验证的是「HTTP over abstract socket」，这里验证的是「WebSocket 会话」。
 */
object CdpAttach {

    data class Prepared(
        val socketName: String,
        val target: CdpTargetInfo,
    )

    /** 选一个可用的 page target。优先挑不在 about:blank 的页面。 */
    suspend fun prepare(): Prepared = withContext(Dispatchers.IO) {
        val probe = CdpProbe.listTargets()
        val pages = probe.targets.filter { it.webSocketDebuggerUrl.isNotBlank() }
        val target = pages.firstOrNull { it.type == "page" && it.url.startsWith("http") }
            ?: pages.firstOrNull { it.type == "page" }
            ?: pages.firstOrNull()
            ?: error(
                "no CDP target. probe errors: " +
                    probe.errors.joinToString("; ").ifBlank { "none" }
            )
        Prepared(probe.socketName, target)
    }

    /**
     * 在选定 target 上执行一条 CDP 命令，返回完整的响应对象（含 result / exceptionDetails）。
     *
     * 调用方负责解读：`Runtime.evaluate` 的结果在 `result.result.value`。
     */
    suspend fun sendOne(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 10_000,
    ): JsonObject = withContext(Dispatchers.IO) {
        val prepared = prepare()
        val conn = LocalSocketCdpTransport(prepared.socketName).open()
        val ws = try {
            WsHandshake.open(conn, WsHandshake.targetPath(prepared.target.webSocketDebuggerUrl))
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw IllegalStateException("WebSocket handshake failed: ${e.message}", e)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = CdpSession(ws, scope)
        session.start(object : CdpSession.EventListener {
            override suspend fun onEvent(method: String, params: JsonObject, sessionId: String?) = Unit
            override suspend fun onClosed() = Unit
        })
        try {
            session.send(method, params, sessionId = null, timeoutMs = timeoutMs)
        } finally {
            runCatching { session.close() }
            runCatching { ws.close() }
            runCatching { conn.close() }
            scope.cancel()
            Log.d("RikkaCdp", "attached ${prepared.target.type} -> $method done")
        }
    }

    /** Runtime.evaluate 的便捷包装，把 `result.result.value` 直接取出来。 */
    suspend fun evaluate(expression: String, timeoutMs: Long = 10_000): JsonObject {
        val resp = sendOne(
            method = "Runtime.evaluate",
            params = buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", true)
            },
            timeoutMs = timeoutMs,
        )
        return buildJsonObject {
            put("raw", resp)
        }
    }
}
