package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 流内错误的状态码归类。
 *
 * 之前所有流内 error 都是 `HttpException(message, statusCode = null)`，
 * 上游把「无状态码」当作可重试，于是 `invalid_request_error`（比如
 * unsupported image）会白白重试 5 次，用户只看到"正在重试 4/5"。
 */
class ErrorParserRetryStatusTest {

    private fun parse(raw: String): HttpException =
        Json.parseToJsonElement(raw).parseErrorDetail()

    @Test
    fun invalidRequestIsClientError() {
        val e = parse("""{"error":{"message":"unsupported image","type":"invalid_request_error"}}""")
        assertEquals(400, e.statusCode!!)
        assertEquals("unsupported image", e.message)
    }

    @Test
    fun insufficientQuotaIsNotRetried() {
        val e = parse("""{"error":{"message":"余额不足","type":"insufficient_quota"}}""")
        assertEquals(402, e.statusCode!!)
    }

    @Test
    fun quotaCodeWins() {
        val e = parse("""{"error":{"message":"quota","code":"insufficient_quota"}}""")
        assertEquals(402, e.statusCode!!)
    }

    @Test
    fun rateLimitStaysRetryable() {
        val e = parse("""{"error":{"message":"slow down","type":"rate_limit_error"}}""")
        assertEquals(429, e.statusCode!!)
    }

    @Test
    fun serverErrorIsRetryable() {
        val e = parse("""{"error":{"message":"boom","type":"server_error"}}""")
        assertEquals(500, e.statusCode!!)
    }

    @Test
    fun unknownTypeKeepsNullStatus() {
        val e = parse("""{"error":{"message":"weird","type":"mystery"}}""")
        assertNull(e.statusCode)
    }

    @Test
    fun plainStringErrorStillParses() {
        val e = parse("\"just a message\"")
        assertEquals("just a message", e.message)
        assertNull(e.statusCode)
    }

    @Test
    fun nestedDetailObjectStillWorks() {
        val e = parse("""{"detail":{"message":"bad"}}""")
        assertEquals("bad", e.message)
    }
}
