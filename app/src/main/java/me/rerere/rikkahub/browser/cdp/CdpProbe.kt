package me.rerere.rikkahub.browser.cdp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * CDP 连通性探针：把「开调试开关 → 找 socket → GET /json 列 target」串成一次调用。
 *
 * 这是整条 CDP 链路最短的自证：能返回 target 列表，就说明
 * `WebView.setWebContentsDebuggingEnabled` + `LocalSocket` abstract socket
 * + 手写 HTTP 这几段在本机是通的，后面的断点/抓包才有地基。
 *
 * 不需要 root：连的是**本进程自己**的 devtools socket。
 */
object CdpProbe {

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val socketName: String,
        val targets: List<CdpTargetInfo>,
        val diagnostics: String,
        val errors: List<String>,
    )

    suspend fun listTargets(): Result = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()

        try {
            WebViewDebugging.setEnabled(true)
        } catch (e: Exception) {
            // suspend 函数不能塞进 runCatching 的普通 lambda，这里用 try/catch。
            errors += "setWebContentsDebuggingEnabled: ${e.javaClass.simpleName}: ${e.message}"
        }

        val discovery = DevToolsSocketResolver.discover()
        discovery.scanError?.let { errors += "scan /proc/net/unix: $it" }

        for (socketName in discovery.candidates) {
            val conn = runCatching { LocalSocketCdpTransport(socketName).open() }
                .onFailure { errors += "$socketName: connect ${it.javaClass.simpleName}: ${it.message}" }
                .getOrNull() ?: continue

            try {
                val body = HttpOverSocket.get(conn, "/json")
                val targets = runCatching {
                    json.decodeFromString<List<CdpTargetInfo>>(body)
                }.onFailure {
                    errors += "$socketName: parse /json ${it.javaClass.simpleName}: ${it.message}"
                }.getOrDefault(emptyList())

                if (targets.isNotEmpty()) {
                    Log.i("RikkaCdp", "probe ok via $socketName: ${targets.size} targets")
                    return@withContext Result(socketName, targets, WebViewDebugging.diagnostics(), errors)
                }
                errors += "$socketName: /json returned no target (no live WebView)"
            } catch (e: Exception) {
                errors += "$socketName: GET /json ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { conn.close() }
            }
        }

        Log.w("RikkaCdp", "probe failed: ${errors.joinToString("; ")}")
        Result("", emptyList(), WebViewDebugging.diagnostics(), errors)
    }
}
