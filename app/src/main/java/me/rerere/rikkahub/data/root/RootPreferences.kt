package me.rerere.rikkahub.data.root

import android.content.Context
import android.content.SharedPreferences

/**
 * Root 相关的本地设置。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有一个字符串，而且 [RootShell]
 * 是个不持有 Context 的纯 JVM 对象，需要同步读取（探测发生在 IO 线程，但
 * 不想为了一个值把整条链改成 suspend + Flow）。
 *
 * 参考 Operit 的做法：允许用户自定义 su 命令。有些 KernelSU / APatch 版本的 su
 * 需要额外参数（比如 `su -mm` 才能挂载 master），写死 "su" 会在这类机器上失败。
 */
object RootPreferences {
    private const val PREFS_NAME = "rikkahub.root"
    private const val KEY_SU_COMMAND = "su_command"

    const val DEFAULT_SU_COMMAND = "su"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 用户配置的 su 命令，可能带参数，例如 `su -mm` 或 `/system/xbin/su -mm`。
     * 空串或纯空白一律回退到 [DEFAULT_SU_COMMAND]。
     */
    fun suCommand(): String =
        prefs?.getString(KEY_SU_COMMAND, DEFAULT_SU_COMMAND)
            ?.trim()
            ?.ifBlank { DEFAULT_SU_COMMAND }
            ?: DEFAULT_SU_COMMAND

    fun setSuCommand(value: String) {
        prefs?.edit()
            ?.putString(KEY_SU_COMMAND, value.trim().ifBlank { DEFAULT_SU_COMMAND })
            ?.apply()
    }

    /** 切词后的 su 命令，处理 `su -mm` 这种带参数的写法。 */
    fun suTokens(): List<String> {
        val tokens = suCommand()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return tokens.ifEmpty { listOf(DEFAULT_SU_COMMAND) }
    }
}
