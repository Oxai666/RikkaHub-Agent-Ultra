package me.rerere.rikkahub.safety

import android.content.Context
import java.io.File

/**
 * 快照放哪。
 *
 * **必须放工作区里**。放 App 私有目录的话两头都瞎：
 *  - AI 的 shell 跑在沙箱里，看不到 `files/safety-snapshots/`
 *  - 用户从文件管理器也进不去私有目录
 *
 * 放工作区文件区之后：
 *  - 路径是 `/workspace/.snapshots/<id>/`
 *  - AI 用 `workspace_shell ls /workspace/.snapshots` 就能查
 *  - 用户在「文件」页也能看到这个目录
 *
 * 只有拿不到 workspaceId 时（比如会话还没绑定工作区）才退回私有目录——
 * 那种情况下本来也没有沙箱可操作。
 */
object SnapshotRoots {

    /** 工作区里的快照目录名。以点开头，跟用户的文件区分开。 */
    const val DIR_NAME = ".snapshots"

    private const val FALLBACK_DIR = "safety-snapshots"

    /**
     * @param workspaceId 助手绑定的工作区 id。为空时退回私有目录。
     */
    fun of(context: Context, workspaceId: String?): File {
        val dir = if (workspaceId.isNullOrBlank()) {
            File(context.filesDir, FALLBACK_DIR)
        } else {
            // 跟 WorkspaceManager 的目录结构对齐：
            //   filesDir/workspaces/<id>/files   ← 这个东西就是沙箱里的 /workspace
            File(context.filesDir, "workspaces/$workspaceId/files/$DIR_NAME")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 沙箱里 `/workspace` 对应的 App 侧真实目录。 */
    fun workspaceFilesRoot(context: Context, workspaceId: String?): File? {
        if (workspaceId.isNullOrBlank()) return null
        return File(context.filesDir, "workspaces/$workspaceId/files")
    }

    /**
     * 把**沙箱路径**翻译成 **App 进程能访问的路径**。
     *
     * 为什么必须有这个：安全层分析 shell 命令时拿到的目标是沙箱视角的
     * `/workspace/test.txt`，而拍快照的代码跑在 App 进程里——
     * `File("/workspace/test.txt")` 在 App 这边是 Android 根目录下，
     * 根本不存在。不做映射的话每个文件都会被判成 existed=false，
     * 一个都备份不上。
     *
     * 沙箱里 `/workspace` 之外的东西（`/etc`、`/opt`）App 进程访问不到，
     * 原样返回——后面 exists 检查会把它当成「不存在」，也就不会误备份。
     */
    fun resolveTarget(
        context: Context,
        workspaceId: String?,
        sandboxPath: String,
    ): File {
        val filesRoot = workspaceFilesRoot(context, workspaceId) ?: return File(sandboxPath)
        return when {
            sandboxPath == "/workspace" -> filesRoot
            sandboxPath.startsWith("/workspace/") ->
                File(filesRoot, sandboxPath.removePrefix("/workspace/"))
            // ⚠ 相对路径按工作区根解析。
            //
            // 这是 .snapshots 一直是空目录的真正原因：
            // 用户敲的是 `cd /workspace && rm -f "新建文本文档.txt"`，
            // 分析器从命令里拿到的目标就是 `新建文本文档.txt`（没有前导 /）。
            // 原来这种情况落到 else 分支 → File("新建文本文档.txt")
            // → 相对 App 进程的 cwd（通常是 /）→ 不存在
            // → capture 里 `!file.exists()` 判成 existed=false，一个字节都不备份。
            //
            // shell 的工作目录是 /workspace，所以相对路径就是相对它。
            !sandboxPath.startsWith("/") -> File(filesRoot, sandboxPath)
            // 沙箱里的绝对路径（/etc、/opt）App 进程访问不到，原样返回，
            // 后面 exists 检查会当成"不存在"，不会误备份。
            else -> File(sandboxPath)
        }
    }
}
