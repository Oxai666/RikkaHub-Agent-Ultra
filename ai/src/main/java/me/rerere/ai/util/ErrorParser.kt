package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String,
    val statusCode: Int? = null,
) : RuntimeException(message)

/**
 * OpenAI / DeepSeek / 兼容网关的 error 对象里 `type` 直接决定该不该重试。
 *
 * 不填状态码时，上游的重试判定 会把
 * 这类错误当成"未知失败"重试 5 次 —— 用户看到的就是"正在重试（第 4/5 次）"，
 * 而真正的原因（比如 unsupported image）埋在重试文案里，很容易被误读成余额不足。
 */
private fun retryStatusForErrorType(type: String?, code: String?): Int? {
    val t = type?.lowercase()
    val c = code?.lowercase()
    return when {
        c == "insufficient_quota" || c == "quota_exceeded" -> 402
        t == "insufficient_quota" -> 402
        t == "invalid_request_error" || t == "invalid_request" -> 400
        t == "authentication_error" || t == "invalid_api_key" -> 401
        t == "permission_error" -> 403
        t == "not_found_error" -> 404
        t == "rate_limit_error" -> 429
        t == "server_error" || t == "api_error" -> 500
        else -> null
    }
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 先认结构化 error 对象，把 type/code 翻译成状态码（决定是否重试）
            (this["error"] as? JsonObject)?.let { node ->
                val type = node["type"]?.jsonPrimitive?.contentOrNull
                val code = node["code"]?.jsonPrimitive?.contentOrNull
                val message = node["message"]?.jsonPrimitive?.contentOrNull
                    ?: Json.encodeToString(JsonElement.serializer(), node)
                return HttpException(message, retryStatusForErrorType(type, code))
            }

            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容 (covers JsonNull too — it's a JsonPrimitive subclass)
            HttpException(this.jsonPrimitive.content)
        }
    }
}
