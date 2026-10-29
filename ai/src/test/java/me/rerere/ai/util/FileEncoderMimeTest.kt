package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * data URL 的 mime 归一化。
 *
 * 背景：`encodeBase64()` 的 data: 分支以前无条件透传，遇到 HEIC/AVIF 或空载荷时
 * 会把坏数据一路发到服务端，换回 `.messages[n].image[0]: unsupported image`，
 * 之后整轮对话每次重试都失败。这里锁住白名单边界。
 */
class FileEncoderMimeTest {

    @Test
    fun whitelistedMimesPassThrough() {
        assertEquals("image/jpeg", normalizeImageMime("image/jpeg"))
        assertEquals("image/png", normalizeImageMime("image/png"))
        assertEquals("image/gif", normalizeImageMime("image/gif"))
        assertEquals("image/webp", normalizeImageMime("image/webp"))
    }

    @Test
    fun jpgAliasNormalisesToJpeg() {
        assertEquals("image/jpeg", normalizeImageMime("image/jpg"))
    }

    @Test
    fun mimeWithParametersIsStripped() {
        assertEquals("image/png", normalizeImageMime("image/png;charset=utf-8"))
        assertEquals("image/jpeg", normalizeImageMime("image/jpeg;base64"))
    }

    @Test
    fun unsupportedFormatsReturnNull() {
        assertNull(normalizeImageMime("image/heic"))
        assertNull(normalizeImageMime("image/heif"))
        assertNull(normalizeImageMime("image/avif"))
        assertNull(normalizeImageMime("image/tiff"))
        assertNull(normalizeImageMime("image/svg+xml"))
        assertNull(normalizeImageMime("application/octet-stream"))
        assertNull(normalizeImageMime(""))
    }

    @Test
    fun caseAndWhitespaceTolerated() {
        assertEquals("image/png", normalizeImageMime("  IMAGE/PNG  "))
    }
}
