package me.rerere.rikkahub.browser.cdp

import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * CDP 连通性探针：开调试开关 → 找 abstract socket → GET /json 列 target。
 *
 * 这是整条 CDP 链路最短的自证。能返回 target 列表，就说明
 * `WebView.setWebContentsDebuggingEnabled` + `LocalSocket` + 手写 HTTP 这三段在本机是通的。
 *
 * 不需要 root：连的是**本进程自己**的 devtools socket。
 *
 * 关于重试：WebView 刚创建时 devtools 端点往往还没开始 accept，第一次连接会
 * 失败或 /json 超时（真机上表现为「no CDP target」）。这里整轮重试 3 次、间隔 300ms，
 * 节奏与太墟的 CdpManager 一致。
 */
object CdpProbe {

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val socketName: String,
        val targets: List<CdpTargetInfo>,
        val diagnostics: String,
        val errors: List<String>,
        val pid: Int,
        val candidates: List<String>,
    )

    suspend fun listTargets(attempts: Int = 3, retryDelayMs: Long = 300L): Result =
        withContext(Dispatchers.IO) {
            var last = probeOnce(1)
            var attempt = 1
            while (attempt < attempts && last.targets.isEmpty()) {
                delay(retryDelayMs)
                attempt++
                last = probeOnce(attempt)
            }
            last
        }

    private suspend fun probeOnce(attempt: Int): Result {
        val errors = ArrayList<String>()

        try {
            WebViewDebugging.setEnabled(true)
        } catch (e: Exception) {
            // setEnabled 是 suspend，不能塞进 runCatching 的普通 lambda。
            errors += "setWebContentsDebuggingEnabled: ${e.javaClass.simpleName}: ${e.message}"
        }

        val pids = DevToolsSocketResolver.packagePids()
        // 把实际拿到的 pid 列表记下来：只有一个候选时，要么 App 真是单进程，
        // 要么 Koin 取不到 Context 退回了 myPid —— 这两者排查方向完全不同。
        if (attempt >= 2) {
            errors += "packagePids=$pids"
        }
        val discovery = DevToolsSocketResolver.discover(pids)
        discovery.scanError?.let { errors += "scan /proc/net/unix: $it" }

        for (socketName in discovery.candidates) {
            val conn = runCatching { LocalSocketCdpTransport(socketName).open() }
                .onFailure {
                    if (attempt >= 3) {
                        errors += "$socketName: connect ${it.javaClass.simpleName}: ${it.message}"
                    }
                }
                .getOrNull() ?: continue

            try {
                val body = HttpOverSocket.get(conn, "/json")
                val targets = runCatching {
                    json.decodeFromString<List<CdpTargetInfo>>(body)
                }.onFailure {
                    errors += "$socketName: parse /json ${it.javaClass.simpleName}: ${it.message}"
                }.getOrDefault(emptyList())

                if (targets.isNotEmpty()) {
                    Log.i("RikkaCdp", "probe ok via $socketName: ${targets.size} targets (attempt $attempt)")
                    return Result(
                        socketName = socketName,
                        targets = targets,
                        diagnostics = WebViewDebugging.diagnostics(),
                        errors = errors,
                        pid = Process.myPid(),
                        candidates = discovery.candidates,
                    )
                }
                if (attempt >= 3) {
                    errors += "$socketName: /json returned no target (WebView alive but no page?)"
                }
            } catch (e: Exception) {
                if (attempt >= 3) {
                    errors += "$socketName: GET /json ${e.javaClass.simpleName}: ${e.message}"
                }
            } finally {
                runCatching { conn.close() }
            }
        }

        if (attempt >= 3) {
            Log.w("RikkaCdp", "probe failed: ${errors.joinToString("; ")}")
        }
        return Result(
            socketName = "",
            targets = emptyList(),
            diagnostics = WebViewDebugging.diagnostics(),
            errors = errors,
            pid = Process.myPid(),
            candidates = discovery.candidates,
        )
    }
}
