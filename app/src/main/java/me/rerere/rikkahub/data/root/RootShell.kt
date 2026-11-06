package me.rerere.rikkahub.data.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 权限入口。
 *
 * 只做一件事：用 `su -c` 跑一条命令，把 stdout / stderr / exitCode 原样带回来。
 * 不假设具体实现（Magisk / KernelSU / APatch 都只是提供一个 su 可执行文件），
 * 也不绑定任何第三方 SDK。
 *
 * 为什么每次调用都重新探测 su：用户可能刚装好 KernelSU 才回来点测试，
 * 缓存住"没有 root"会让整个设置页一直显示不可用。探测代价是一次
 * File.canExecute()，可以忽略。
 */
object RootShell {

    /** 输出上限 256K 字符，防止 `cat /dev/urandom`、巨型 dmesg 把内存吃光。 */
    private const val MAX_OUTPUT_CHARS = 256 * 1024

    /** 探测 su 时用的短超时：授权弹窗可能卡住，不能等太久。 */
    private const val PROBE_TIMEOUT_MS = 8_000L

    val defaultTimeoutMs = 60_000L
    val minTimeoutMs = 1_000L
    val maxTimeoutMs = 300_000L

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /**
     * su 的常见落点。包含 `/data/adb/ksu/bin/su`（KernelSU）和 `/data/adb/magisk/su`（Magisk），
     * 因为部分 ROM 上这两个不在 PATH 里。
     */
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/debug_ramdisk/su",
    )

    /** 最近一次探测到的 su 绝对路径，null 表示还没探测过。 */
    @Volatile
    private var cachedSu: String? = null

    @Volatile
    private var probed: Boolean = false

    /** 探测结果（绝对路径），没有 root 时为 null。 */
    fun suPath(): String? {
        if (probed) return cachedSu
        val found = suCandidates.firstNotNullOfOrNull { candidate ->
            if (candidate.contains('/')) {
                File(candidate).takeIf { it.exists() && it.canExecute() }?.absolutePath
            } else {
                which(candidate)
            }
        }
        cachedSu = found
        probed = true
        return found
    }

    /** 丢掉缓存，强制下次重新探测（用户在设置页点「重新检测」时用）。 */
    fun invalidate() {
        probed = false
        cachedSu = null
    }

    private fun which(name: String): String? {
        val path = System.getenv("PATH").orEmpty()
        if (path.isBlank()) return null
        return path.split(':').asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    }

    /**
     * su 是否可用，并且真的能拿到 uid 0。
     *
     * 只看 `su` 文件存在是不够的：Magisk 在未授权时也放着这个文件，
     * 真正能执行才算数。所以这里跑一次 `id -u` 并核对输出。
     */
    suspend fun isAvailable(): Boolean {
        invalidate()
        val su = suPath() ?: return false
        val result = run(su, "id -u", PROBE_TIMEOUT_MS)
        return result.ok && result.stdout.trim() == "0"
    }

    /** 探测详情，给设置页显示用。 */
    suspend fun probe(): Probe {
        invalidate()
        val su = suPath() ?: return Probe(available = false, suPath = null, detail = "没有找到 su 可执行文件")
        val id = run(su, "id -u", PROBE_TIMEOUT_MS)
        if (id.timedOut) {
            return Probe(available = false, suPath = su, detail = "su 超时未响应（授权弹窗被拦下了？）")
        }
        if (!id.ok) {
            return Probe(
                available = false,
                suPath = su,
                detail = "su 退出码 ${id.exitCode}：${id.stderr.trim().ifBlank { "无输出" }}",
            )
        }
        if (id.stdout.trim() != "0") {
            return Probe(available = false, suPath = su, detail = "su 返回的 uid 不是 0：${id.stdout.trim()}")
        }
        // 能拿到 uid 0 之后，再取一行版本信息，方便确认是哪套 root 方案
        val version = run(su, "getprop ro.build.version.release; uname -r", PROBE_TIMEOUT_MS)
        return Probe(
            available = true,
            suPath = su,
            detail = version.stdout.trim().lines().filter { it.isNotBlank() }.joinToString(" / "),
        )
    }

    data class Probe(
        val available: Boolean,
        val suPath: String?,
        val detail: String,
    )

    /** 执行一条命令。su 不存在时直接返回失败，不抛异常。 */
    suspend fun exec(command: String, timeoutMs: Long = defaultTimeoutMs): Result {
        val su = suPath() ?: return Result(-1, "", "su not found: 这台设备没有可用的 root shell")
        return run(su, command, timeoutMs.coerceIn(minTimeoutMs, maxTimeoutMs))
    }

    private suspend fun run(su: String, command: String, timeoutMs: Long): Result =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(false)
                    .start()
            } catch (t: Throwable) {
                return@withContext Result(-1, "", "failed to start su: ${t.message}")
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // stdout / stderr 必须并行读：管道缓冲区满了之后，写端会阻塞，
            // 单线程先读完 stdout 再读 stderr 会直接死锁。
            val outReader = Thread { drain(process.inputStream.bufferedReader(), stdout) }
            val errReader = Thread { drain(process.errorStream.bufferedReader(), stderr) }
            outReader.isDaemon = true
            errReader.isDaemon = true
            outReader.start()
            errReader.start()

            val finished = try {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                return@withContext Result(-1, stdout.toString(), "interrupted", true)
            }

            if (!finished) {
                process.destroyForcibly()
                outReader.join(IMPL_JOIN_MS)
                errReader.join(IMPL_JOIN_MS)
                return@withContext Result(
                    exitCode = -1,
                    stdout = stdout.toString(),
                    stderr = "timed out after ${timeoutMs}ms",
                    timedOut = true,
                )
            }

            outReader.join(IMPL_JOIN_MS)
            errReader.join(IMPL_JOIN_MS)
            Result(
                exitCode = try {
                    process.exitValue()
                } catch (t: IllegalThreadStateException) {
                    -1
                },
                stdout = stdout.toString(),
                stderr = stderr.toString(),
            )
        }

    private const val IMPL_JOIN_MS = 2_000L

    private fun drain(reader: java.io.Reader, sink: StringBuilder) {
        try {
            val buffer = CharArray(8 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (sink.length >= MAX_OUTPUT_CHARS) continue
                val room = MAX_OUTPUT_CHARS - sink.length
                sink.append(buffer, 0, minOf(read, room))
            }
        } catch (_: Throwable) {
            // 进程被强杀时读端会抛，忽略即可
        } finally {
            runCatching { reader.close() }
        }
    }
}
