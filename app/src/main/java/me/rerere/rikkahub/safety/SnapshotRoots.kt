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
}
