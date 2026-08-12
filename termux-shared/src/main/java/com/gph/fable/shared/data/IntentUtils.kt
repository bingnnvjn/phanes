package com.gph.fable.shared.data

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.NonNull
import java.util.Arrays

object IntentUtils {

    private const val LOG_TAG = "IntentUtils"

    /**
     * Get a [String] extra from an [Intent] if its not `null` or empty.
     */
    @Throws(Exception::class)
    @JvmStatic
    fun getStringExtraIfSet(@NonNull intent: Intent, key: String, def: String?, throwExceptionIfNotSet: Boolean): String? {
        val value = getStringExtraIfSet(intent, key, def)
        if (value == null && throwExceptionIfNotSet)
            throw Exception("The \"" + key + "\" key string value is null or empty")
        return value
    }

    /**
     * Get a [String] extra from an [Intent] if its not `null` or empty.
     */
    @JvmStatic
    fun getStringExtraIfSet(@NonNull intent: Intent, key: String, def: String?): String? {
        val value = intent.getStringExtra(key)
        if (value == null || value.isEmpty()) {
            return if (def != null && !def.isEmpty())
                def
            else
                null
        }
        return value
    }

    /**
     * Get an [Int] from an [Intent] stored as a [String] extra if its not `null` or empty.
     */
    @JvmStatic
    fun getIntegerExtraIfSet(@NonNull intent: Intent, key: String, def: Int?): Int? {
        return try {
            val value = intent.getStringExtra(key)
            if (value == null || value.isEmpty()) {
                return def
            }

            Integer.parseInt(value)
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get a [Array] of [String] extra from an [Intent] if its not `null` or empty.
     */
    @Throws(Exception::class)
    @JvmStatic
    fun getStringArrayExtraIfSet(@NonNull intent: Intent, key: String, def: Array<String>?, throwExceptionIfNotSet: Boolean): Array<String>? {
        val value = getStringArrayExtraIfSet(intent, key, def)
        if (value == null && throwExceptionIfNotSet)
            throw Exception("The \"" + key + "\" key string array is null or empty")
        return value
    }

    /**
     * Get a [Array] of [String] extra from an [Intent] if its not `null` or empty.
     */
    @JvmStatic
    fun getStringArrayExtraIfSet(intent: Intent, key: String, def: Array<String>?): Array<String>? {
        val value = intent.getStringArrayExtra(key)
        if (value == null || value.size == 0) {
            return if (def != null && def.size != 0)
                def
            else
                null
        }
        return value
    }

    @JvmStatic
    fun getIntentString(intent: Intent?): String? {
        if (intent == null) return null

        return intent.toString() + "\n" + getBundleString(intent.extras)
    }

    @JvmStatic
    fun getBundleString(bundle: Bundle?): String {
        if (bundle == null || bundle.size() == 0) return "Bundle[]"

        val bundleString = StringBuilder("Bundle[\n")
        var first = true
        for (key in bundle.keySet()) {
            if (!first)
                bundleString.append("\n")

            bundleString.append(key).append(": `")

            val value = bundle.get(key)
            when (value) {
                is IntArray -> bundleString.append(Arrays.toString(value))
                is ByteArray -> bundleString.append(Arrays.toString(value))
                is BooleanArray -> bundleString.append(Arrays.toString(value))
                is ShortArray -> bundleString.append(Arrays.toString(value))
                is LongArray -> bundleString.append(Arrays.toString(value))
                is FloatArray -> bundleString.append(Arrays.toString(value))
                is DoubleArray -> bundleString.append(Arrays.toString(value))
                is Array<*> -> {
                    if (value is Array<*> && value.isArrayOf<String>())
                        bundleString.append(Arrays.toString(value as Array<String>))
                    else if (value is Array<*> && value.isArrayOf<CharSequence>())
                        bundleString.append(Arrays.toString(value as Array<CharSequence>))
                    else if (value is Array<*> && value.isArrayOf<Parcelable>())
                        bundleString.append(Arrays.toString(value as Array<Parcelable>))
                    else
                        bundleString.append(value)
                }
                is Bundle -> bundleString.append(getBundleString(value))
                else -> bundleString.append(value)
            }

            bundleString.append("`")

            first = false
        }

        bundleString.append("\n]")
        return bundleString.toString()
    }
}
