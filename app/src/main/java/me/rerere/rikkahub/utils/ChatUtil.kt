package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip"
)

/** 这些 mime 虽然不以 text/ 开头，但内容确实是文本，可以内联。 */
private val INLINE_READABLE_MIME = setOf(
    "application/json", "application/javascript", "application/xml",
    "application/x-yaml", "application/yaml", "application/x-sh",
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml", "agc",
    "py", "rb", "lua", "luau", "sql", "java", "kt", "ts", "tsx",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "rs", "cs", "markdown", "mdx",
    "toml", "ini", "env", "gradle", "kts", "properties",
    "proto", "graphql", "gql", "yml", "yaml"
)

/**
 * 附件类型限制。
 *
 * 以前只认一份白名单（文本 / 代码 / Office / PDF），日志、压缩包、二进制全被拒。
 * 现在**全放行**：选什么都能带上来，能不能「读」交给下面的 [isInlineReadableFile] 判断，
 * 读不了的就只挂文件 + 给模型一条隐藏提示，让它走文件工具去处理。
 */
fun isAllowedFileType(fileName: String, mime: String): Boolean = true

/** 压缩包 / 二进制：这些不该把内容塞进上下文，只把文件挂上去。 */
private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "jar", "apk", "aab", "aar"
)

private val BINARY_EXTENSIONS = setOf(
    "bin", "so", "dex", "oat", "vdex", "img", "iso", "db", "sqlite", "sqlite3",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub",
    "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif", "ico",
    "mp3", "wav", "flac", "aac", "ogg", "opus", "m4a",
    "mp4", "mkv", "mov", "webm", "avi"
)

/**
 * 这个文件的内容能不能直接读给模型看。
 *
 * true  = 文本类，可以内联进上下文
 * false = 压缩包 / 二进制 / 媒体，只能挂文件，让模型用工具去读
 */
fun isInlineReadableFile(fileName: String, mime: String): Boolean {
    if (mime.startsWith("text/")) return true
    if (mime in INLINE_READABLE_MIME) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension in ARCHIVE_EXTENSIONS || extension in BINARY_EXTENSIONS) return false
    // 代码 / 配置这类扩展名（原白名单里的）都算可读
    return extension in ALLOWED_FILE_EXTENSIONS
}

/** 这个 Text part 是不是「只给模型看」的隐藏提示（比如附件说明）。 */
fun UIMessagePart.isHiddenHint(): Boolean =
    this is UIMessagePart.Text &&
        metadata?.get("hidden")?.jsonPrimitive?.booleanOrNull == true

/** 读不了的文件，挂上去之后给模型的隐藏提示。 */
fun attachmentHintFor(fileName: String, mime: String): String? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when {
        extension in ARCHIVE_EXTENSIONS ->
            "[$extension：已复制到沙箱] " +
                "需要通过工具访问时，请使用文件/工作区工具解压或读取；" +
                "不要使用 Android content:// URI 或宿主机路径。"
        extension in BINARY_EXTENSIONS ->
            "[$extension：二进制文件，已复制到沙箱] " +
                "内容不能直接内联，请用文件工具按需读取或转换；" +
                "不要使用 Android content:// URI 或宿主机路径。"
        mime.startsWith("text/") || extension in ALLOWED_FILE_EXTENSIONS -> null
        else ->
            "[$extension：已复制到沙箱] " +
                "需要通过工具访问时，请使用文件/工作区工具读取；" +
                "不要使用 Android content:// URI 或宿主机路径。"
    }
}
