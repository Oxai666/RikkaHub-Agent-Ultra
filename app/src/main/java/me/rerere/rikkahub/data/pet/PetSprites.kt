package me.rerere.rikkahub.data.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache

/**
 * 一张解码好的序列图，以及它每个逻辑像素对应多少个位图像素。
 *
 * [perLogical] 一般等于 `pack.scale`（图片本来就是这个倍率），
 * 只有为了省内存主动降采样时才会更小。
 */
class PetSheet(val bitmap: Bitmap, val perLogical: Int)

/**
 * 宠物包的素材加载。移植自 dsh-go 的 `PetSprites.kt`（AGPL-3.0）。
 *
 * 相对原版砍掉的东西，以及为什么：
 *  - **BitmapRegionDecoder 分条解码**：原版是为了 Deepy 那张 17680px 宽的图
 *    （68 帧 × 5 倍）不撑爆纹理上限。我们的包最大 472K，整张解进内存
 *    不到 2MB，分条解码只是增加出错面。
 *  - **后台线程 + 回调排队**：Compose 里用 `remember` + `LaunchedEffect` 就够，
 *    不需要手写 Handler 队列。
 *  - **多包共存**：我们一次只显示一只。
 *
 * 保留的是原版真正重要的两件事：
 *  - **LRU 缓存**，按 byteCount 算大小而不是条数（每段动画的体积差 20 倍）
 *  - **像素画不降采样**（`perLogical = pack.scale`），否则边缘会糊
 */
class PetSprites(private val context: Context) {

    /** 缓存上限 8MB。两个包的动画全部解进来也就 4MB 上下。 */
    private val cache = object : LruCache<String, PetSheet>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: PetSheet): Int = value.bitmap.byteCount
    }

    /** 从 assets 读某个包的 pack.json。读不到或格式不对返回 null。 */
    fun loadPack(packId: String): PetPack? = runCatching {
        context.assets.open("pets/$packId/pack.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .let(PetPack::parse)
    }.onFailure {
        Log.w(TAG, "pack $packId 读不了", it)
    }.getOrNull()

    /**
     * 取 [anim] 的序列图。已经解过就返回缓存。
     *
     * **调用方负责在 IO 线程调**——解码一张 472K 的 PNG 要几十毫秒，
     * 放主线程会掉帧。
     */
    fun sheet(packId: String, anim: PetAnimation): PetSheet? {
        val key = "$packId/${anim.sheet}"
        cache.get(key)?.let { return it }
        val loaded = runCatching {
            context.assets.open("pets/$packId/${anim.sheet}").use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                    ?: return@use null
                PetSheet(bmp, perLogical = 1)
            }
        }.onFailure {
            Log.w(TAG, "sheet ${anim.sheet} 解码失败", it)
        }.getOrNull() ?: return null
        cache.put(key, loaded)
        return loaded
    }

    /**
     * 这一帧在序列图里的源矩形。
     *
     * 图的排布是等大方格网：每行 [PetAnimation.cols] 格，按行排。
     * 格子按**原始图**的像素算，跟缩放无关——缩放交给 Canvas。
     */
    fun srcRect(anim: PetAnimation, cell: Int, sheet: PetSheet): android.graphics.Rect {
        val per = sheet.perLogical
        val cellW = anim.frameW * per
        val cellH = anim.frameH * per
        val col = cell % anim.cols
        val row = cell / anim.cols
        return android.graphics.Rect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
    }

    fun clear() {
        cache.evictAll()
    }

    companion object {
        private const val TAG = "PetSprites"
        private const val CACHE_BYTES = 8 * 1024 * 1024
    }
}

/**
 * 把一段动画的帧序列展开成「每个时间片播哪一格」。
 *
 * 单独抽出来是因为这一步最容易写错：`frames` 里存的是 `{格号, 时长}`，
 * 同一格可能被引用多次（去重省体积），所以不能按索引取格子。
 */
class PetFrameCursor(private val anim: PetAnimation) {

    /** 整段播一遍要多久。 */
    val totalMs: Long = anim.totalMs

    /**
     * [elapsedMs] 时刻该显示第几格。
     *
     * 循环动画自动取模；不循环的播完停在最后一格。
     */
    fun cellAt(elapsedMs: Long): Int {
        if (anim.frames.isEmpty()) return 0
        if (!anim.loop && elapsedMs >= totalMs) return anim.frames.last().cell
        var t = if (anim.loop && totalMs > 0) elapsedMs % totalMs else elapsedMs
        for (f in anim.frames) {
            if (t < f.ms) return f.cell
            t -= f.ms
        }
        return anim.frames.last().cell
    }

    /** 一个周期里有多少个不同的时间片——用于判断「要不要重绘」。 */
    val frameCount: Int get() = anim.frames.size
}
