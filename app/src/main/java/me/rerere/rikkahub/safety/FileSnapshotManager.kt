package me.rerere.rikkahub.safety

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 文件快照：不可逆操作之前的备份，以及失败后的回滚。
 *
 * 为什么必须有这个：`RiskAssessor` 一直在产出 `SnapshotPlan(required = true)`，
 * 但从来没有代码去执行它。那比不做更糟——用户看到「已备份」的暗示，
 * 实际上什么都没存。
 *
 * 设计取舍：
 *  - **只备份、不打包**。每个文件单独复制，保留原目录结构。打包成 zip 看起来
 *    干净，但恢复单个文件要解压整个包，而且中途失败就没法续。
 *  - **有容量上限**。手机上不能无限备份，单文件 8MB、整次快照 64MB 封顶，
 *    超了就记进 manifest 的 skipped 里，让调用方知道哪个没保住。
 *  - **快照目录跟工作区平级**，不在 rootfs 里。沙箱被删了快照还在。
 */
object FileSnapshotManager {

    private const val TAG = "FileSnapshotManager"
    private const val DIR_NAME = "safety-snapshots"
    private const val MANIFEST_NAME = "manifest.json"
    private const val MAX_SNAPSHOTS = 20
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 一个被备份的文件。 */
    @Serializable
    data class Entry(
        val originalPath: String,
        /** 在快照目录里的相对路径。为空表示这个文件当时不存在，或备份失败。 */
        val snapshotPath: String = "",
        /** 拍快照那一刻这个文件存不存在。false 意味着回滚时要把它删掉。 */
        val existed: Boolean,
        val bytes: Long = 0L,
        /** 备份失败的原因（超限、读不了）。空表示成功。 */
        val skipped: String? = null,
    )

    /** 一次快照。 */
    @Serializable
    data class Record(
        val id: String,
        val createdAtMs: Long,
        val toolName: String,
        val entries: List<Entry>,
    )

    /** 回滚结果。 */
    data class RestoreResult(
        val restored: Int,
        val deleted: Int,
        val failed: List<String>,
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /**
     * 快照根目录。
     *
     * 由调用方传进来（见 [SnapshotRoots.of]），因为根目录跟工作区绑定——
     * 放 App 私有目录的话，AI 的沙箱 shell 和文件管理器都看不到。
     */
    fun rootDir(root: File): File = root.apply { if (!exists()) mkdirs() }

    /**
     * 按计划拍快照。
     *
     * 返回 null 表示一个文件都没拍到——调用方应该把 `required` 的快照失败
     * 当成拒绝执行的理由，而不是「备份失败但继续跑」。
     */
    fun capture(
        root: File,
        plan: SnapshotPlan,
        toolName: String,
        /**
         * 把计划里的**沙箱路径**翻译成 App 进程能访问的路径。
         *
         * 默认原样返回，那是给「路径本来就是 App 侧」的场景用的。
         * 从 shell 命令分析出来的目标全是沙箱视角的 `/workspace/...`，
         * 必须由调用方传映射函数，否则一个文件都找不到、一个都备份不上。
         */
        resolveTarget: (String) -> File = { File(it) },
    ): Record? {
        if (plan.scope == SnapshotScope.NONE || plan.targets.isEmpty()) return null

        val id = System.currentTimeMillis().toString()
        val snapshotDir = File(rootDir(root), id)
        val filesDir = File(snapshotDir, "files")
        if (!filesDir.mkdirs()) {
            Log.w(TAG, "capture: cannot create $filesDir")
            return null
        }

        var totalBytes = 0L
        var captured = 0
        val entries = mutableListOf<Entry>()
        // 同一个路径在一次操作里出现两次时只备份第一次。
        // 之前的 copyTo(overwrite = true) 会让后一次覆盖前一次的副本，
        // 回滚拿到的是「第二次」的状态，不是操作前的状态。
        // 这就是原实现里 SnapshotConflictPolicy 要处理的情况，
        // 这里选了固定的「先到先得」——比隐式覆盖清楚。
        val seen = mutableSetOf<String>()

        for (target in plan.targets) {
            val path = target.path
            if (!seen.add(path)) continue
            val file = resolveTarget(path)

            // 目录不备份：递归复制一整棵树代价太大，而且删除目录时
            // 真正有价值的是里面的文件，调用方应该把文件列出来而不是给目录。
            if (!file.exists() || !file.isFile) {
                entries += Entry(originalPath = path, existed = false)
                continue
            }

            val size = file.length()
            if (size > MAX_FILE_BYTES) {
                entries += Entry(
                    originalPath = path,
                    existed = true,
                    bytes = size,
                    skipped = "超过单文件上限 ${MAX_FILE_BYTES / 1024 / 1024}MB",
                )
                continue
            }
            if (totalBytes + size > MAX_TOTAL_BYTES) {
                entries += Entry(
                    originalPath = path,
                    existed = true,
                    bytes = size,
                    skipped = "超过本次快照总上限 ${MAX_TOTAL_BYTES / 1024 / 1024}MB",
                )
                continue
            }

            // 快照内的相对路径：去掉开头的 /，再把 .. 之类的路径片段压平，
            // 防止逃出快照目录
            val relative = path.trimStart('/')
                .replace("..", "__")
                .replace(':', '_')
            val dest = File(filesDir, relative)
            dest.parentFile?.mkdirs()

            val ok = runCatching { file.copyTo(dest, overwrite = true) }.isSuccess
            if (!ok) {
                entries += Entry(
                    originalPath = path,
                    existed = true,
                    bytes = size,
                    skipped = "复制失败",
                )
                continue
            }

            totalBytes += size
            captured++
            entries += Entry(
                originalPath = path,
                snapshotPath = relative,
                existed = true,
                bytes = size,
            )
        }

        if (captured == 0) {
            snapshotDir.deleteRecursively()
            Log.w(TAG, "capture: nothing captured for $toolName")
            return null
        }

        val record = Record(
            id = id,
            createdAtMs = System.currentTimeMillis(),
            toolName = toolName,
            entries = entries,
        )
        runCatching {
            File(snapshotDir, MANIFEST_NAME).writeText(json.encodeToString(record))
        }.onFailure {
            Log.w(TAG, "capture: manifest write failed", it)
            snapshotDir.deleteRecursively()
            return null
        }

        prune(root)
        return record
    }

    /**
     * 把快照里的内容复制回原位。
     *
     * 两条分支都要处理：
     *  - 当时**有**这个文件 → 复制回去，覆盖现在的版本
     *  - 当时**没有**这个文件 → 把现在存在的删掉，回到「那时候还没有」的状态
     */
    fun restore(
        root: File,
        record: Record,
        /** 跟 [capture] 的同一个映射，恢复时也要用它把沙箱路径翻回 App 路径。 */
        resolveTarget: (String) -> File = { File(it) },
    ): RestoreResult {
        val snapshotDir = File(rootDir(root), record.id)
        var restored = 0
        var deleted = 0
        val failed = mutableListOf<String>()

        for (entry in record.entries) {
            val target = resolveTarget(entry.originalPath)
            if (!entry.existed) {
                // 拍的时候没有，现在有 → 删掉
                if (target.exists() && target.isFile) {
                    if (runCatching { target.delete() }.getOrDefault(false)) deleted++
                    else failed += entry.originalPath
                }
                continue
            }
            if (entry.snapshotPath.isEmpty()) {
                failed += entry.originalPath
                continue
            }
            val source = File(File(snapshotDir, "files"), entry.snapshotPath)
            if (!source.isFile) {
                failed += entry.originalPath
                continue
            }
            val ok = runCatching {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }.isSuccess
            if (ok) restored++ else failed += entry.originalPath
        }

        return RestoreResult(restored = restored, deleted = deleted, failed = failed)
    }

    fun list(root: File): List<Record> {
        val dir = rootDir(root)
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val manifest = File(dir, MANIFEST_NAME)
                if (!manifest.isFile) return@mapNotNull null
                runCatching {
                    json.decodeFromString<Record>(manifest.readText())
                }.getOrNull()
            }
            .sortedByDescending { it.createdAtMs }
    }

    fun recordById(root: File, id: String): Record? =
        list(root).firstOrNull { it.id == id }

    /** 只保留最近 [keep] 份，其余删掉。 */
    fun prune(root: File, keep: Int = MAX_SNAPSHOTS) {
        val all = list(root)
        if (all.size <= keep) return
        val dir = rootDir(root)
        all.drop(keep).forEach { record ->
            runCatching { File(dir, record.id).deleteRecursively() }
        }
    }

    /** 删掉某一份快照（回滚成功后调用）。 */
    fun discard(root: File, record: Record) {
        runCatching { File(rootDir(root), record.id).deleteRecursively() }
    }

    /** 清空全部。设置页里给它一个入口。 */
    fun clearAll(root: File) {
        runCatching { rootDir(root).deleteRecursively() }
        rootDir(root)
    }
}
