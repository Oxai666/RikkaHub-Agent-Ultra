package me.rerere.rikkahub.data.pet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * 宠物包解析。移植自 dsh-go 的 `PetPack.kt`（AGPL-3.0 源码）。
 *
 * 格式是 Clawd on Desk 的主题包规范；素材放在 `assets/pets/<id>/pack.json`。
 * 这里只做解析，不碰 Android 类型，所以能在 JVM 单测里跑。
 *
 * 相对原版改的地方：
 *  - `Json.parse` 换成 kotlinx.serialization（原版用项目自己的 Json 工具）
 *  - 去掉了 `windowBox()` / `crisp` / 窗口相关的成员——那些是给悬浮窗算尺寸用的，
 *    我们只做 App 内宠物，尺寸规则见 [screenScale]
 */

/** 一帧：图里第几格，停多久。 */
data class PetFrame(val cell: Int, val ms: Int)

/** 三档尺寸，按**身体高度**定（contentBox.h），不按画布大小。 */
enum class PetSize(val dp: Float) {
    SMALL(22f),
    MEDIUM(30f),
    LARGE(40f);

    companion object {
        fun fromName(name: String?): PetSize = when (name) {
            "small" -> SMALL
            "large" -> LARGE
            else -> MEDIUM
        }
    }
}

/** 逻辑像素（包内画布坐标系）里的矩形。 */
data class PetBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun union(o: PetBox): PetBox {
        val l = minOf(x, o.x)
        val t = minOf(y, o.y)
        return PetBox(l, t, maxOf(right, o.right) - l, maxOf(bottom, o.bottom) - t)
    }
}

/**
 * 一段动画：等大方格网（每行 [cols] 格，按行排），每格是 [box] 在画布上的位置。
 */
data class PetAnimation(
    val name: String,
    val sheet: String,
    /** 单格尺寸，单位是图里的像素（= box × scale）。 */
    val frameW: Int,
    val frameH: Int,
    val cols: Int,
    val box: PetBox,
    val frames: List<PetFrame>,
    val loop: Boolean,
) {
    val totalMs: Long = frames.sumOf { it.ms.toLong() }
    val cells: Int get() = (frames.maxOfOrNull { it.cell } ?: 0) + 1
    val rows: Int get() = (cells + cols - 1) / cols
}

data class PetTier(val min: Int, val anim: String)

/** 待机池里的一项：播哪段、播多久、多大权重。 */
data class PetIdleItem(val anim: PetAnimation, val ms: Long, val weight: Int)

/** 某个状态的彩蛋替换动画（`bonus`，比如鲸娘 attention 时的比心）。 */
data class PetBonus(val anims: List<PetAnimation>, val chance: Float)

/**
 * 运行时宠物包。
 *
 * 状态名沿用 Clawd on Desk（idle / thinking / working / juggling / error / attention /
 * notification / sweeping / carrying / sleeping / waking / roam），
 * 两套素材因此可以通用。
 */
class PetPack(
    val id: String,
    val name: String,
    val pixelArt: Boolean,
    /** 图里的像素 / 逻辑像素：鲸娘是 1，Deepy 是 5（身体画在半像素上）。 */
    val scale: Int,
    val canvasW: Int,
    val canvasH: Int,
    val anchorX: Float,
    /** 脚线：身体最低不透明行的下沿，放到站位上沿。 */
    val baseline: Float,
    /** 待机身体框：气泡指向它，也是尺寸档位的依据。 */
    val contentBox: PetBox,
    val animations: Map<String, PetAnimation>,
    val states: Map<String, List<String>>,
    val tiers: Map<String, List<PetTier>>,
    val reactions: Map<String, String>,
    val playMs: Map<String, Long>,
    val idlePool: List<PetIdleItem>,
    val bonus: Map<String, PetBonus>?,
    private val defaultHitBox: PetBox? = null,
    private val hitBoxByAnim: Map<String, PetBox> = emptyMap(),
) {
    /** 命中区：量在身体上，思考云 / Zzz / 音符不算。 */
    fun hitBoxFor(anim: PetAnimation): PetBox =
        hitBoxByAnim[anim.name] ?: defaultHitBox ?: contentBox

    /** [state] 该播哪段：先看有没有够档的 tier，否则用状态的主动画。 */
    fun primary(state: String, tier: Int): PetAnimation {
        tiers[state]?.let { list ->
            list.firstOrNull { tier >= it.min }?.let { t -> animations[t.anim]?.let { return it } }
        }
        states[state]?.firstOrNull()?.let { animations[it] }?.let { return it }
        if (state != IDLE) return primary(IDLE, 0)
        return idlePool.firstOrNull()?.anim ?: animations.values.first()
    }

    /** 状态的偶发替换动画。 */
    fun bonusOf(state: String, tier: Int): PetBonus? {
        bonus?.let { return it[state] }
        if (state == IDLE || tiers[state]?.any { tier >= it.min } == true) return null
        val p = primary(state, tier)
        val rest = states[state].orEmpty().mapNotNull { animations[it] }.filter { it !== p }
        return if (rest.isEmpty()) null else PetBonus(rest, 1f / 3)
    }

    fun reaction(kind: String): PetAnimation? = reactions[kind]?.let { animations[it] }

    /** 一次性反应播多久：`timings.playMs`，否则两遍（循环）或一遍（单次）。 */
    fun playMsOf(anim: PetAnimation): Long =
        playMs[anim.name] ?: (if (anim.loop) 2 * anim.totalMs else anim.totalMs)

    /**
     * [size] 档在某屏幕密度下每个美术像素占多少**物理像素**。
     *
     * 像素画要求整数——不然缩放时边缘会糊。而且三档必须至少各差 1：
     * 2.75× 的手机上 22/30/40dp 对着 49px 的身体会同时落成 1/2/2，
     * 三档看起来一样大。非像素画按目标高度直接算。
     */
    fun devicePixels(size: PetSize, density: Float): Int {
        fun at(s: PetSize): Int =
            (s.dp.toDouble() * density / contentBox.h).roundToInt().coerceAtLeast(1)

        val small = at(PetSize.SMALL)
        val medium = maxOf(small + 1, at(PetSize.MEDIUM))
        val large = maxOf(medium + 1, at(PetSize.LARGE))
        return when (size) {
            PetSize.SMALL -> small
            PetSize.MEDIUM -> medium
            PetSize.LARGE -> large
        }
    }

    fun screenScale(size: PetSize, density: Float): Float =
        if (pixelArt) devicePixels(size, density).toFloat() else size.dp * density / contentBox.h

    companion object {
        const val IDLE = "idle"

        /** 认识的 Clawd 状态名。其余名字一律按 idle 播。 */
        val STATES = setOf(
            "idle", "thinking", "working", "juggling", "sweeping", "notification",
            "attention", "error", "carrying", "sleeping", "waking", "roam",
        )

        fun parse(text: String): PetPack {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: bad("not an object")
            if (num(root["schema"])?.toInt() != 1) bad("schema must be 1")
            val id = (root.str("id"))?.takeIf { it.isNotBlank() } ?: bad("missing id")
            val scale = num(root["scale"])?.toInt()?.takeIf { it >= 1 } ?: bad("missing scale")
            val canvas = root.obj("canvas") ?: bad("missing canvas")
            val canvasW = num(canvas["w"])?.toInt()?.takeIf { it > 0 } ?: bad("bad canvas")
            val canvasH = num(canvas["h"])?.toInt()?.takeIf { it > 0 } ?: bad("bad canvas")

            val animsRaw = root.obj("animations") ?: bad("missing animations")
            val animations = LinkedHashMap<String, PetAnimation>()
            for ((name, v) in animsRaw) {
                val a = v as? JsonObject ?: bad("bad animation $name")
                val sheet = a.str("sheet") ?: bad("$name: no sheet")
                if (sheet.contains('/') || sheet.contains('\\') || sheet.startsWith('.')) {
                    bad("$name: bad sheet")
                }
                val box = box(a["box"]) ?: bad("$name: no box")
                val fw = num(a["frameW"])?.toInt() ?: bad("$name: no frameW")
                val fh = num(a["frameH"])?.toInt() ?: bad("$name: no frameH")
                if (fw != (box.w * scale).toInt() || fh != (box.h * scale).toInt()) {
                    bad("$name: cell ≠ box × scale")
                }
                val cols = num(a["cols"])?.toInt()?.takeIf { it >= 1 } ?: bad("$name: no cols")
                val frames = (a["frames"] as? JsonArray).orEmpty().map { f ->
                    val m = f as? JsonObject ?: bad("$name: bad frame")
                    val cell = num(m["i"])?.toInt()?.takeIf { it >= 0 }
                        ?: bad("$name: bad frame index")
                    val ms = num(m["ms"])?.toInt()?.takeIf { it > 0 }
                        ?: bad("$name: bad frame time")
                    PetFrame(cell, ms)
                }
                if (frames.isEmpty()) bad("$name: no frames")
                val loop = (a["loop"] as? JsonPrimitive)?.booleanOrNull != false
                animations[name] = PetAnimation(name, sheet, fw, fh, cols, box, frames, loop)
            }
            if (animations.isEmpty()) bad("no animations")

            fun ref(e: JsonElement?, where: String): String {
                val n = (e as? JsonPrimitive)?.contentOrNull()
                return if (n != null && n in animations) n else bad("$where names a missing animation")
            }

            val states = LinkedHashMap<String, List<String>>()
            root.obj("states")?.forEach { (k, v) ->
                val list = (v as? JsonArray).orEmpty().map { ref(it, "states.$k") }
                if (list.isNotEmpty()) states[k] = list
            }

            val tiers = LinkedHashMap<String, List<PetTier>>()
            root.obj("tiers")?.forEach { (k, v) ->
                val list = (v as? JsonArray).orEmpty().map { t ->
                    val m = t as? JsonObject ?: bad("tiers.$k")
                    PetTier(num(m["min"])?.toInt() ?: 1, ref(m["anim"], "tiers.$k"))
                }.sortedByDescending { it.min }
                if (list.isNotEmpty()) tiers[k] = list
            }

            val reactions = LinkedHashMap<String, String>()
            root.obj("reactions")?.forEach { (k, v) -> reactions[k] = ref(v, "reactions.$k") }
            root.obj("roam")?.get("anim")?.let { ref(it, "roam") }

            val playMs = LinkedHashMap<String, Long>()
            (root.obj("timings")?.get("playMs") as? JsonObject)?.forEach { (k, v) ->
                val ms = num(v)?.toLong()
                if (ms != null && ms > 0) playMs[k] = ms
            }

            val hits = root.obj("hitBoxes")
            val wideBox = box(hits?.get("wide"))
            val sleepingBox = box(hits?.get("sleeping"))
            val hitBoxByAnim = LinkedHashMap<String, PetBox>()
            (hits?.get("wideAnims") as? JsonArray).orEmpty()
                .filterIsInstance<JsonPrimitive>()
                .mapNotNull { it.contentOrNull }
                .forEach { wideBox?.let { b -> hitBoxByAnim[it] = b } }
            (hits?.get("sleepingAnims") as? JsonArray).orEmpty()
                .filterIsInstance<JsonPrimitive>()
                .mapNotNull { it.contentOrNull }
                .forEach { sleepingBox?.let { b -> hitBoxByAnim[it] = b } }
            (hits?.get("byAnim") as? JsonObject)?.forEach { (k, v) ->
                box(v)?.let { hitBoxByAnim[k] = it }
            }

            val poolRaw = (root.obj("idlePool")?.get("anims") ?: root["idlePool"]) as? JsonArray
            val idlePool = poolRaw?.map { item ->
                val m = item as? JsonObject ?: bad("idlePool")
                val anim = animations.getValue(ref(m["anim"], "idlePool"))
                PetIdleItem(
                    anim,
                    num(m["ms"])?.toLong()?.takeIf { it > 0 } ?: (2 * anim.totalMs),
                    (num(m["weight"])?.toInt() ?: 1).coerceAtLeast(1),
                )
            } ?: states[IDLE].orEmpty().drop(1).map { name ->
                val anim = animations.getValue(name)
                PetIdleItem(anim, playMs[name] ?: (2 * anim.totalMs), 1)
            }

            val bonus = if ("bonus" in root.keys) LinkedHashMap<String, PetBonus>() else null
            root.obj("bonus")?.forEach { (k, v) ->
                val m = v as? JsonObject ?: return@forEach
                val anims = (m["anims"] as? JsonArray).orEmpty()
                    .map { animations.getValue(ref(it, "bonus.$k")) }
                val chance = (num(m["chance"])?.toFloat() ?: 0f).coerceIn(0f, 1f)
                bonus?.put(k, PetBonus(anims, chance))
            }

            val anchor = root.obj("anchor")
            val firstBox = animations.values.first().box
            return PetPack(
                id = id,
                name = root.str("name") ?: id,
                pixelArt = (root["pixelArt"] as? JsonPrimitive)?.booleanOrNull == true,
                scale = scale,
                canvasW = canvasW,
                canvasH = canvasH,
                anchorX = num(anchor?.get("x"))?.toFloat() ?: (canvasW / 2f),
                baseline = num(anchor?.get("baseline"))?.toFloat() ?: canvasH.toFloat(),
                contentBox = box(root["contentBox"]) ?: firstBox,
                animations = animations,
                states = states,
                tiers = tiers,
                reactions = reactions,
                playMs = playMs,
                idlePool = idlePool,
                bonus = bonus,
                defaultHitBox = box(hits?.get("default")),
                hitBoxByAnim = hitBoxByAnim,
            )
        }

        private fun bad(why: String): Nothing = throw PetPackException(why)

        private fun num(v: JsonElement?): Number? = when (v) {
            is JsonPrimitive -> v.intOrNull ?: v.floatOrNull
            else -> null
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull()

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun box(v: JsonElement?): PetBox? {
            val m = v as? JsonObject ?: return null
            val x = num(m["x"])?.toFloat() ?: return null
            val y = num(m["y"])?.toFloat() ?: return null
            val w = num(m["w"])?.toFloat()?.takeIf { it > 0 } ?: return null
            val h = num(m["h"])?.toFloat()?.takeIf { it > 0 } ?: return null
            return PetBox(x, y, w, h)
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

class PetPackException(message: String) : Exception(message)
