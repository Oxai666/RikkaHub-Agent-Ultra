package me.rerere.rikkahub.browser.cdp

import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val pid: Int = Process.myPid(),
        val candidates: List<String> = emptyList(),
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

        // WebView 刚创建时 devtools 服务端可能还没开始 accept，按 300ms × 3 重试。
        // 太墟的 CdpManager 也是这个节奏（失败重试 3×300ms），照抄。
        for (attempt in 0 until 3) {
            for (socketName in discovery.candidates) {
                val conn = runCatching { LocalSocketCdpTransport(socketName).open() }
                    .onFailure {
                        if (attempt == 2) {
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
                        Log.i("RikkaCdp", "probe ok via $socketName: ${targets.size} targets (attempt ${attempt + 1})")
                        return@withContext Result(
                            socketName, targets, WebViewDebugging.diagnostics(), errors,
                            Process.myPid(), discovery.candidates,
                        )
                    }
                    if (attempt == 2) {
                        errors += "$socketName: /json returned no target (WebView alive but no page?)"
                    }
                } catch (e: Exception) {
                    if (attempt == 2) {
                        errors += "$socketName: GET /json ${e.javaClass.simpleName}: ${e.message}"
                    }
                } finally {
                    runCatching { conn.close() }
                }
            }
            if (attempt < 2) delay(300)
        }

        Log.w("RikkaCdp", "probe failed: pid=${Process.myPid()} candidates=${discovery.candidates} errors=${errors.joinToString("; ")}")
        Result("", emptyList(), WebViewDebugging.diagnostics(), errors, Process.myPid(), discovery.candidates)
    }
}
