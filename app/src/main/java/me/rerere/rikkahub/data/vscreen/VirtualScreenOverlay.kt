package me.rerere.rikkahub.data.vscreen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * 虚拟屏的悬浮小窗。
 *
 * 和 [me.rerere.rikkahub.ui.pages.setting.vscreen.VirtualScreenWindowPage] 的分工：
 * 那个是全屏大窗（专心操作），这个是悬浮窗（一边刷手机一边看着，或者切到别的应用时还能点）。
 *
 * 布局是代码搭的，没用 Compose：
 *  - Compose 放进 WindowManager 需要自己提供 ViewTreeLifecycleOwner 之类的，
 *    为了一个悬浮窗引入那套东西不划算；
 *  - 而且这里只需要一个 ImageView + 两个手势区，View 体系反而更短。
 */
object VirtualScreenOverlay {

    private const val PREFS = "rikkahub.vscreen.overlay"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var imageView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var streamJob: Job? = null

    /** 当前这一帧，用来算点击坐标落在哪 */
    private var frameWidth = 0
    private var frameHeight = 0

    fun isShowing(): Boolean = root != null

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 没有悬浮窗权限时跳系统设置页。返回 true 表示已经可以直接显示。 */
    fun ensurePermission(context: Context): Boolean {
        if (canDrawOverlays(context)) return true
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (isShowing()) return
        if (!ensurePermission(context)) return

        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = app.resources.displayMetrics.density
        val widthPx = (170 * density).toInt()
        val heightPx = (300 * density).toInt()

        val container = FrameLayout(app)
        val image = ImageView(app).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        container.addView(image)

        // 顶部拖动条：只有这一条区域负责拖窗，其余区域留给「点 / 滑屏幕」
        val dragBar = FrameLayout(app).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (32 * density).toInt(),
                Gravity.TOP,
            )
        }
        // 中间那根白色小横杠：告诉用户「这里能拖」
        dragBar.addView(
            View(app).apply {
                setBackgroundColor(Color.argb(210, 255, 255, 255))
                layoutParams = FrameLayout.LayoutParams(
                    (56 * density).toInt(),
                    (4 * density).toInt(),
                    Gravity.CENTER,
                )
            }
        )
        container.addView(dragBar)

        // 关闭按钮：默认藏起来，点一下顶部白条才冒出来，避免误触
        val closeButton = android.widget.TextView(app).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 190, 40, 40))
            layoutParams = FrameLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt(),
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = (2 * density).toInt()
                rightMargin = (2 * density).toInt()
            }
            visibility = View.GONE
            setOnClickListener { hide() }
        }
        container.addView(closeButton)

        val lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（输入法仍能用到底层应用），但保留触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefsGet(app, KEY_X, 16)
            y = prefsGet(app, KEY_Y, 240)
        }
        params = lp

        attachDrag(app, wm, container, dragBar, closeButton, lp)
        attachGestures(context, image)

        runCatching { wm.addView(container, lp) }
        root = container
        imageView = image

        startStream()
    }

    fun hide() {
        streamJob?.cancel()
        streamJob = null
        scope?.cancel()
        scope = null
        val wm = windowManager
        val view = root
        if (wm != null && view != null) runCatching { wm.removeView(view) }
        root = null
        imageView = null
        windowManager = null
        params = null
    }

    private fun startStream() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        streamJob = s.launch {
            while (isActive) {
                runCatching {
                    VirtualScreenManager.stream { bitmap ->
                        frameWidth = bitmap.width
                        frameHeight = bitmap.height
                        val view = imageView
                        if (view != null) {
                            view.post {
                                val iw = view.width
                                val ih = view.height
                                // 等比缩小到窗口大小，别把 720x1280 的原图直接塞进去
                                if (iw > 0 && ih > 0) {
                                    val scale = min(iw.toFloat() / bitmap.width, ih.toFloat() / bitmap.height)
                                    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                                    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                                    view.setImageBitmap(scaled)
                                    if (scaled !== bitmap) bitmap.recycle()
                                } else {
                                    view.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(
        context: Context,
        wm: WindowManager,
        container: FrameLayout,
        dragBar: View,
        closeButton: View,
        lp: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        dragBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) + abs(dy) > 12f) moved = true
                    lp.x = (startX - dx).toInt()
                    lp.y = (startY + dy).toInt()
                    runCatching { wm.updateViewLayout(container, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 拖过 → 记住位置
                        prefsPut(context, KEY_X, lp.x)
                        prefsPut(context, KEY_Y, lp.y)
                    } else {
                        // 没拖 → 当成点击：切换关闭按钮的显示
                        closeButton.visibility =
                            if (closeButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * 画面区域的点按与滑动。
     *
     * 一次手势结束才发命令（不是实时流式转发）：
     *  - 按下到抬起位移小于 24px 且不超过 500ms → tap
     *  - 否则 → swipe（起点、终点、真实耗时）
     *
     * 实时转发每个 move 事件也能做，但每次都要起一个 `input` 进程，
     * 十几毫秒一个根本跟不上，反而更卡。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(context: Context, image: ImageView) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        image.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = (System.currentTimeMillis() - downAt).toInt()
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    if (moved < 24f && elapsed < 500) {
                        forwardTap(view, event.x, event.y)
                    } else {
                        forwardSwipe(view, downX, downY, event.x, event.y, elapsed)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    /** 视图坐标 → 虚拟屏像素。返回 null 表示落在 FIT_CENTER 的黑边上。 */
    private fun mapToScreen(view: View, x: Float, y: Float): Pair<Float, Float>? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val iw = view.width
        val ih = view.height
        if (iw <= 0 || ih <= 0) return null
        val scale = min(iw.toFloat() / frameWidth, ih.toFloat() / frameHeight)
        val drawW = frameWidth * scale
        val drawH = frameHeight * scale
        val offsetX = (iw - drawW) / 2f
        val offsetY = (ih - drawH) / 2f
        val vx = (x - offsetX) / scale
        val vy = (y - offsetY) / scale
        if (vx < 0 || vy < 0 || vx > frameWidth || vy > frameHeight) return null
        return vx to vy
    }

    private fun forwardTap(view: View, x: Float, y: Float) {
        val mapped = mapToScreen(view, x, y) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            VirtualScreenManager.tap(mapped.first, mapped.second)
        }
    }

    private fun forwardSwipe(view: View, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        val from = mapToScreen(view, x1, y1) ?: return
        val to = mapToScreen(view, x2, y2) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            VirtualScreenManager.swipe(
                from.first, from.second, to.first, to.second,
                durationMs.coerceIn(80, 2_000).toLong(),
            )
        }
    }

    private fun prefsGet(context: Context, key: String, fallback: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, fallback)

    private fun prefsPut(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }
}
