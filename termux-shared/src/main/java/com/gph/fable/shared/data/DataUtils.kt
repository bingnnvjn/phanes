package com.gph.fable.shared.data

import android.os.Bundle
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.common.base.Strings
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object DataUtils {

    /** Max safe limit of data size to prevent TransactionTooLargeException when transferring data
     * inside or to other apps via transactions. */
    const val TRANSACTION_SIZE_LIMIT_IN_BYTES = 100 * 1024 // 100KB

    private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

    @JvmStatic
    fun getTruncatedCommandOutput(text: String?, maxLength: Int, fromEnd: Boolean, onNewline: Boolean, addPrefix: Boolean): String? {
        if (text == null) return null

        var text = text
        var maxLength = maxLength
        val prefix = "(truncated) "

        if (addPrefix)
            maxLength = maxLength - prefix.length

        if (maxLength < 0 || text.length < maxLength) return text

        if (fromEnd) {
            text = text.substring(0, maxLength)
        } else {
            var cutOffIndex = text.length - maxLength

            if (onNewline) {
                val nextNewlineIndex = text.indexOf('\n', cutOffIndex)
                if (nextNewlineIndex != -1 && nextNewlineIndex != text.length - 1) {
                    cutOffIndex = nextNewlineIndex + 1
                }
            }
            text = text.substring(cutOffIndex)
        }

        if (addPrefix)
            text = prefix + text

        return text
    }

    /**
     * Replace a sub string in each item of a [Array].
     */
    @JvmStatic
    fun replaceSubStringsInStringArrayItems(array: Array<String>?, find: String, replace: String) {
        if (array == null || array.size == 0) return

        for (i in array.indices) {
            array[i] = array[i].replace(find, replace)
        }
    }

    /**
     * Get the `float` from a [String].
     */
    @JvmStatic
    fun getFloatFromString(value: String?, def: Float): Float {
        if (value == null) return def

        return try {
            value.toFloat()
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get the `int` from a [String].
     */
    @JvmStatic
    fun getIntFromString(value: String?, def: Int): Int {
        if (value == null) return def

        return try {
            value.toInt()
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get the [String] from an [Int].
     */
    @JvmStatic
    fun getStringFromInteger(value: Int?, def: String?): String? {
        return if (value == null) def else value.toString()
    }

    /**
     * Get the `hex string` from a [ByteArray].
     */
    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Get an `int` from [Bundle] that is stored as a [String].
     */
    @JvmStatic
    fun getIntStoredAsStringFromBundle(bundle: Bundle?, key: String, def: Int): Int {
        if (bundle == null) return def
        return getIntFromString(bundle.getString(key, Integer.toString(def)), def)
    }

    /**
     * If value is not in the range [min, max], set it to either min or max.
     */
    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.min(Math.max(value, min), max)
    }

    /**
     * If value is not in the range [min, max], set it to default.
     */
    @JvmStatic
    fun rangedOrDefault(value: Float, def: Float, min: Float, max: Float): Float {
        return if (value < min || value > max)
            def
        else
            value
    }

    /**
     * Add a space indent to a [String]. Each indent is 4 space characters long.
     */
    @JvmStatic
    fun getSpaceIndentedString(string: String?, count: Int): String? {
        return if (string == null || string.isEmpty())
            string
        else
            getIndentedString(string, "    ", count)
    }

    /**
     * Add a tab indent to a [String]. Each indent is 1 tab character long.
     */
    @JvmStatic
    fun getTabIndentedString(string: String?, count: Int): String? {
        return if (string == null || string.isEmpty())
            string
        else
            getIndentedString(string, "\t", count)
    }

    /**
     * Add an indent to a [String].
     */
    @JvmStatic
    fun getIndentedString(string: String?, @NonNull indent: String, count: Int): String? {
        return if (string == null || string.isEmpty())
            string
        else
            string.replace(Regex("(?m)^"), Strings.repeat(indent, Math.max(count, 1)))
    }

    /**
     * Get the object itself if it is not `null`, otherwise default.
     */
    @JvmStatic
    fun <T> getDefaultIfNull(@Nullable obj: T?, @Nullable def: T?): T? {
        return obj ?: def
    }

    /**
     * Get the [String] itself if it is not `null` or empty, otherwise default.
     */
    @JvmStatic
    fun getDefaultIfUnset(@Nullable value: String?, def: String?): String? {
        return if (value == null || value.isEmpty()) def else value
    }

    /** Check if a string is null or empty. */
    @JvmStatic
    fun isNullOrEmpty(string: String?): Boolean {
        return string == null || string.isEmpty()
    }

    /** Get size of a serializable object. */
    @JvmStatic
    fun getSerializedSize(obj: Serializable?): Long {
        if (obj == null) return 0
        return try {
            val byteOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(byteOutputStream)
            objectOutputStream.writeObject(obj)
            objectOutputStream.flush()
            objectOutputStream.close()
            byteOutputStream.toByteArray().size.toLong()
        } catch (e: Exception) {
            -1
        }
    }
}
