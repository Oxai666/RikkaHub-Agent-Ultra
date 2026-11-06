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

        // 顶部拖动条：只有这一条区域负责拖窗，其余区域留给「点屏幕」
        val dragBar = View(app).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (26 * density).toInt(),
                Gravity.TOP,
            )
        }
        container.addView(dragBar)

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

        attachDrag(app, wm, container, dragBar, lp)
        attachTap(context, image)

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
    private fun attachDrag(
        context: Context,
        wm: WindowManager,
        container: FrameLayout,
        dragBar: View,
        lp: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        dragBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX - (event.rawX - touchX)).toInt()
                    lp.y = (startY + (event.rawY - touchY)).toInt()
                    runCatching { wm.updateViewLayout(container, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefsPut(context, KEY_X, lp.x)
                    prefsPut(context, KEY_Y, lp.y)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 画面区域的点击 → 换算成虚拟屏像素 → 发 tap。
     *
     * FIT_CENTER 会在窗口里留黑边，所以要先减掉绘制偏移再除缩放比，
     * 落在黑边上的触摸直接忽略。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTap(context: Context, image: ImageView) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        image.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    val elapsed = System.currentTimeMillis() - downAt
                    // 只有「几乎没动 + 时间短」才当点击，避免滑动窗口时误触
                    if (moved < 24f && elapsed < 400) {
                        forwardTap(context, view, event.x, event.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun forwardTap(context: Context, view: View, x: Float, y: Float) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val iw = view.width
        val ih = view.height
        if (iw <= 0 || ih <= 0) return
        val scale = min(iw.toFloat() / frameWidth, ih.toFloat() / frameHeight)
        val drawW = frameWidth * scale
        val drawH = frameHeight * scale
        val offsetX = (iw - drawW) / 2f
        val offsetY = (ih - drawH) / 2f
        val vx = (x - offsetX) / scale
        val vy = (y - offsetY) / scale
        if (vx < 0 || vy < 0 || vx > frameWidth || vy > frameHeight) return

        CoroutineScope(Dispatchers.IO).launch {
            VirtualScreenManager.tap(vx, vy)
        }
    }

    private fun prefsGet(context: Context, key: String, fallback: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, fallback)

    private fun prefsPut(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }
}
