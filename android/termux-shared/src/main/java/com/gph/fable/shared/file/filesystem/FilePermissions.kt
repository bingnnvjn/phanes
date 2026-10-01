package com.gph.fable.shared.file.filesystem

import com.gph.fable.shared.file.filesystem.FilePermission.*
import java.util.*

/**
 * This class consists exclusively of static methods that operate on sets of
 * [FilePermission] objects.
 */
object FilePermissions {

    // Write string representation of permission bits to `sb`.
    private fun writeBits(sb: StringBuilder, r: Boolean, w: Boolean, x: Boolean) {
        if (r) {
            sb.append('r')
        } else {
            sb.append('-')
        }
        if (w) {
            sb.append('w')
        } else {
            sb.append('-')
        }
        if (x) {
            sb.append('x')
        } else {
            sb.append('-')
        }
    }

    /**
     * Returns the [String] representation of a set of permissions.
     */
    @JvmStatic
    fun toString(perms: Set<FilePermission>): String {
        val sb = StringBuilder(9)
        writeBits(sb, perms.contains(OWNER_READ), perms.contains(OWNER_WRITE),
            perms.contains(OWNER_EXECUTE))
        writeBits(sb, perms.contains(GROUP_READ), perms.contains(GROUP_WRITE),
            perms.contains(GROUP_EXECUTE))
        writeBits(sb, perms.contains(OTHERS_READ), perms.contains(OTHERS_WRITE),
            perms.contains(OTHERS_EXECUTE))
        return sb.toString()
    }

    private fun isSet(c: Char, setValue: Char): Boolean {
        return when {
            c == setValue -> true
            c == '-' -> false
            else -> throw IllegalArgumentException("Invalid mode")
        }
    }

    private fun isR(c: Char): Boolean {
        return isSet(c, 'r')
    }

    private fun isW(c: Char): Boolean {
        return isSet(c, 'w')
    }

    private fun isX(c: Char): Boolean {
        return isSet(c, 'x')
    }

    /**
     * Returns the set of permissions corresponding to a given [String] representation.
     */
    @JvmStatic
    fun fromString(perms: String): Set<FilePermission> {
        if (perms.length != 9)
            throw IllegalArgumentException("Invalid mode")
        val result = EnumSet.noneOf(FilePermission::class.java)
        if (isR(perms[0])) result.add(OWNER_READ)
        if (isW(perms[1])) result.add(OWNER_WRITE)
        if (isX(perms[2])) result.add(OWNER_EXECUTE)
        if (isR(perms[3])) result.add(GROUP_READ)
        if (isW(perms[4])) result.add(GROUP_WRITE)
        if (isX(perms[5])) result.add(GROUP_EXECUTE)
        if (isR(perms[6])) result.add(OTHERS_READ)
        if (isW(perms[7])) result.add(OTHERS_WRITE)
        if (isX(perms[8])) result.add(OTHERS_EXECUTE)
        return result
    }
}
