package com.gph.fable.app.session

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 最近会话存储路径编码/解码（工单 28 Kotlin 试点）。
 *
 * 行为与旧 Java 实现完全等价：UTF-8 URL 编码；解码失败返回 null。
 */
object RecentSessionPathCodec {

    private const val UTF_8 = "UTF-8"

    @JvmStatic
    fun encode(path: String): String {
        return try {
            URLEncoder.encode(path, UTF_8)
        } catch (e: UnsupportedEncodingException) {
            path
        }
    }

    @JvmStatic
    fun decode(encoded: String?): String? {
        if (encoded == null) return null
        return try {
            URLDecoder.decode(encoded, UTF_8)
        } catch (e: UnsupportedEncodingException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
