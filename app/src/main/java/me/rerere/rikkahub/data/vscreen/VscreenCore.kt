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
            val mirror = opts["mirror"]?.toBooleanStrictOrNull() ?: false

            // 主 Looper 必须先建：ActivityThread 构造时就会创建 Handler，
            // 没有 Looper 会直接抛 "Can't create handler inside thread that has not
            // called Looper.prepare()"。
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper()
            }

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
                virtualDisplayFlags(mirror),
            ) ?: error("createVirtualDisplay returned null")

            val server = ServerSocket(port)

            // socket 服务放后台线程，主线程交给 Looper.loop()：
            // ActivityThread 的 handler 需要主线程在 loop，否则 DisplayManager
            // 的回调可能永远不投递。
            Thread {
                serve(server, reader, width, height, virtualDisplay)
                // serve 只在 quit / 连接异常时返回；主线程还卡在 Looper.loop()，
                // 所以直接结束整个进程，别让僵尸进程留在后台。
                kotlin.system.exitProcess(0)
            }.apply { isDaemon = true }.start()

            // 父进程靠这一行确认「起来了并且端口是对的」，之后才去连
            println("VSCREEN_READY $port $width $height")
            System.out.flush()

            android.os.Looper.loop()
        } catch (t: Throwable) {
            // 父进程读 stderr 判断失败原因；带上堆栈第一层，方便定位
            val where = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            System.err.println("VSCREEN_ERROR ${t.javaClass.simpleName}: ${t.message} @ $where")
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

    /**
     * 组装 VirtualDisplay 的 flags。
     *
     * `OWN_CONTENT_ONLY` 是分水岭：
     *  - 有它 → 独立的一块屏，只有主动投上去的 Activity 才会显示（默认）
     *  - 没有 → 镜像主显示（你手机现在显示什么，它就显示什么）
     *
     * 后面几个常量在不同 API 级别是 hidden 的，Kotlin 编译期看不到，
     * 所以一律用反射取名字，取不到就跳过 —— 少了它们只会损失触摸/焦点能力，
     * 不会让创建失败。
     */
    private fun virtualDisplayFlags(mirror: Boolean): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        if (!mirror) {
            flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        }
        for (name in listOf(
            "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
            "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
            // TRUSTED 需要系统签名，普通 su 进程带上反而会被拒；不加。
        )) {
            flags = flags or runCatching {
                DisplayManager::class.java.getField(name).getInt(null)
            }.getOrDefault(0)
        }
        return flags
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
     * 这一段全是照着 DSHA 的 PrivilegedPackageContext 抄的，顺序不能变：
     *
     *  1. 主 Looper 必须先建 —— ActivityThread 构造时会创建 Handler
     *  2. **不能调 ActivityThread.systemMain()**：它会跑 ROM 的 attach 钩子，
     *     在 app_process 里有副作用；直接 new 一个再自己塞静态字段更轻
     *  3. mSystemThread = true 必须设，否则 getSystemContext() 返回 null
     *  4. sCurrentActivityThread 必须设，否则后续拿到的还是 null
     */
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")

        var thread: Any? = runCatching {
            val current = type.getDeclaredMethod("currentActivityThread")
            current.isAccessible = true
            current.invoke(null)
        }.getOrNull()

        if (thread == null) {
            val ctor = type.getDeclaredConstructor()
            ctor.isAccessible = true
            thread = ctor.newInstance()
        }

        // 无论新建还是已有的，都补上这两个字段：缺一个 getSystemContext 就返回 null
        runCatching {
            val systemThread = type.getDeclaredField("mSystemThread")
            systemThread.isAccessible = true
            systemThread.setBoolean(thread, true)
        }
        runCatching {
            val current = type.getDeclaredField("sCurrentActivityThread")
            current.isAccessible = true
            current.set(null, thread)
        }

        val getSystemContext = type.getDeclaredMethod("getSystemContext")
        getSystemContext.isAccessible = true
        val context = getSystemContext.invoke(thread) as? Context
            ?: error("ActivityThread.getSystemContext() returned null")
        return context
    }
}
