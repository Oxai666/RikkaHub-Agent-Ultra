package me.rerere.rikkahub.data.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Base64
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * 虚拟屏幕的核心进程。
 *
 * 由 [VirtualScreenManager] 通过 root 以
 * `app_process -Djava.class.path=<apk> /system/bin <本类> --port N ...` 拉起，
 * 跑在一个**独立进程**里，身份是 su（shell 或 root）。
 *
 * 为什么必须另起进程：
 *  - `DisplayManager.createVirtualDisplay` 需要 CAPTURE_VIDEO_OUTPUT（system 级），
 *    普通应用进程拿不到，所以只能借 su 的身份；
 *  - 单独进程崩了也不会把主 App 带走。
 *
 * 协议极简：连上以后发一行命令，回一行结果。
 *   shot      -> `OK <宽度> <高度> <base64-jpeg>` / `ERR <原因>`
 *   size      -> `OK <宽度> <高度>`
 *   ping      -> `OK pong`
 *   quit      -> `OK bye`（随后进程退出）
 *
 * 只做「看」，不做「点」。输入注入要另一套反射（InputManager.injectInputEvent），
 * 第一版先不掺进来，免得两个都不稳。
 */
object VscreenCore {

    private const val CLASS_NAME = "me.rerere.rikkahub.data.vscreen.VscreenCore"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val opts = parse(args)
            val width = opts["width"]?.toIntOrNull() ?: 720
            val height = opts["height"]?.toIntOrNull() ?: 1280
            val dpi = opts["dpi"]?.toIntOrNull() ?: 320
            val port = opts["port"]?.toIntOrNull() ?: 8765

            val context = systemContext()
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // maxImages = 2：够「拿上一帧 + 正在产生的一帧」，再多只会占内存
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay: VirtualDisplay = displayManager.createVirtualDisplay(
                "rikkahub-vscreen",
                width,
                height,
                dpi,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            ) ?: error("createVirtualDisplay returned null")

            val server = ServerSocket(port)
            // 父进程靠这一行确认「起来了并且端口是对的」，之后才去连
            println("VSCREEN_READY $port $width $height")
            System.out.flush()

            serve(server, reader, width, height, virtualDisplay)
        } catch (t: Throwable) {
            // 父进程读 stderr 判断失败原因
            System.err.println("VSCREEN_ERROR ${t.javaClass.simpleName}: ${t.message}")
            System.err.flush()
            kotlin.system.exitProcess(1)
        }
    }

    private fun serve(
        server: ServerSocket,
        reader: ImageReader,
        width: Int,
        height: Int,
        display: VirtualDisplay,
    ) {
        var running = true
        while (running) {
            val socket: Socket = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            socket.use { client ->
                client.soTimeout = 20_000
                val readerIn = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val out = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)
                val command = runCatching { readerIn.readLine() }.getOrNull()?.trim().orEmpty()
                when (command) {
                    "ping" -> out.println("OK pong")
                    "size" -> out.println("OK $width $height")
                    "shot" -> out.println(capture(reader, width, height))
                    "quit" -> {
                        out.println("OK bye")
                        running = false
                    }
                    else -> out.println("ERR unknown command: $command")
                }
            }
        }
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { server.close() }
    }

    /**
     * 抓一帧转 JPEG。
     *
     * acquireLatestImage 会返回**最近**的一帧；虚拟屏刚创建时可能还没有内容，
     * 所以这里等最多 1.5 秒，等不到就返回空图（而不是抛异常）。
     */
    private fun capture(reader: ImageReader, width: Int, height: Int): String {
        var image: android.media.Image? = null
        val deadline = System.currentTimeMillis() + 1_500
        while (image == null && System.currentTimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(30)
        }
        val acquired = image ?: return "ERR no frame yet"
        return try {
            val plane = acquired.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            // rowStride 可能大于 width * pixelStride（有 padding），要逐行拷贝
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (rowStride == width * pixelStride) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowBytes = width * pixelStride
                val padded = ByteArray(rowStride)
                val target = ByteArray(rowBytes * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    val toRead = minOf(rowStride, buffer.remaining())
                    buffer.get(padded, 0, toRead)
                    System.arraycopy(padded, 0, target, row * rowBytes, rowBytes)
                }
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(target))
            }
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
            bitmap.recycle()
            "OK $width $height ${Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)}"
        } catch (t: Throwable) {
            "ERR ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { acquired.close() }
        }
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val key = args[i]
            if (key.startsWith("--") && i + 1 < args.size) {
                map[key.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        return map
    }

    /**
     * 在没有 Activity 的进程里拿 Context。
     *
     * ActivityThread.systemMain() 会复用系统进程的 ActivityThread（app_process 里
     * 通常已经有一个），拿到的是 system context —— 它带 shell/root 权限，
     * 能调用 createVirtualDisplay。
     */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = runCatching {
            val systemMain = activityThread.getDeclaredMethod("systemMain")
            systemMain.isAccessible = true
            systemMain.invoke(null)
        }.getOrNull()
        val instance = thread ?: runCatching {
            val current = activityThread.getDeclaredMethod("currentActivityThread")
            current.isAccessible = true
            current.invoke(null)
        }.getOrNull() ?: error("no ActivityThread in this process")
        val getSystemContext = activityThread.getDeclaredMethod("getSystemContext")
        getSystemContext.isAccessible = true
        return getSystemContext.invoke(instance) as Context
    }
}
