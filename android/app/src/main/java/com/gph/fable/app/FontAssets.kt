package com.gph.fable.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** Copies the uncompressed emoji assets and hands their paths to the renderer. */
object FontAssets {
    private const val TAG = "FableFontAssets"
    private val fonts = arrayOf("AppleColorEmoji.ttf", "NotoColorEmoji.ttf")
    private const val APPLE_SHA256 =
        "6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3"
    private const val NOTO_SHA256 =
        "0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2"

    @JvmStatic
    @Synchronized
    fun install(context: Context?, rendererHandle: Long) {
        if (context == null) return
        val dir = File(context.filesDir, "fonts")
        fonts.forEach { name ->
            val output = File(dir, name)
            val expected = expectedSha(name)
            if (output.exists() && expected != null && sha256(output) == expected) return@forEach
            copyAsset(context, name, output)
        }
        if (rendererHandle != 0L) {
            RenderCore.rendererSetFontPaths(
                rendererHandle,
                File(dir, "AppleColorEmoji.ttf").absolutePath,
                File(dir, "NotoColorEmoji.ttf").absolutePath
            )
        }
    }

    private fun copyAsset(context: Context, name: String, output: File): Boolean {
        return try {
            context.assets.open("fonts/$name").use { input ->
                output.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) return false
                }
                FileOutputStream(output).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        outputStream.write(buffer, 0, count)
                    }
                }
            }
            expectedSha(name)?.let { sha256(output) == it } ?: true
        } catch (e: IOException) {
            Log.e(TAG, "copyAsset 失败: $name", e)
            false
        }
    }

    private fun expectedSha(name: String): String? = when (name) {
        "AppleColorEmoji.ttf" -> APPLE_SHA256
        "NotoColorEmoji.ttf" -> NOTO_SHA256
        else -> null
    }

    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: IOException) {
            ""
        } catch (_: NoSuchAlgorithmException) {
            ""
        }
    }
}
