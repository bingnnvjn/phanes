package com.gph.fable.shared.file.filesystem

import androidx.annotation.NonNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Objects
import java.util.concurrent.TimeUnit

/**
 * Represents the value of a file's time stamp attribute.
 */
class FileTime private constructor(
    private val value: Long,
    private val unit: TimeUnit
) {

    /**
     * The value return by toString (created lazily)
     */
    private var valueAsString: String? = null

    /**
     * Returns the value at the given unit of granularity.
     */
    fun to(unit: TimeUnit): Long {
        Objects.requireNonNull(unit, "unit")
        return unit.convert(this.value, this.unit)
    }

    /**
     * Returns the value in milliseconds.
     */
    fun toMillis(): Long {
        return unit.toMillis(value)
    }

    @NonNull
    override fun toString(): String {
        return getDate(toMillis(), "yyyy.MM.dd HH:mm:ss.SSS z")
    }

    companion object {

        /**
         * Returns a [FileTime] representing a value at the given unit of granularity.
         */
        @JvmStatic
        fun from(value: Long, @NonNull unit: TimeUnit): FileTime {
            Objects.requireNonNull(unit, "unit")
            return FileTime(value, unit)
        }

        /**
         * Returns a [FileTime] representing the given value in milliseconds.
         */
        @JvmStatic
        fun fromMillis(value: Long): FileTime {
            return FileTime(value, TimeUnit.MILLISECONDS)
        }

        @JvmStatic
        fun getDate(milliSeconds: Long, format: String): String {
            return try {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = milliSeconds
                SimpleDateFormat(format).format(calendar.time)
            } catch (e: Exception) {
                java.lang.Long.toString(milliSeconds)
            }
        }
    }
}
