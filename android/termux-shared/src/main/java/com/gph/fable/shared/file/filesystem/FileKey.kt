package com.gph.fable.shared.file.filesystem

/**
 * Container for device/inode to uniquely identify file.
 */
class FileKey internal constructor(private val st_dev: Long, private val st_ino: Long) {

    override fun hashCode(): Int {
        return (st_dev xor (st_dev ushr 32)).toInt() +
            (st_ino xor (st_ino ushr 32)).toInt()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj === this)
            return true
        if (obj !is FileKey)
            return false
        return (this.st_dev == obj.st_dev) && (this.st_ino == obj.st_ino)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("(dev=")
            .append(java.lang.Long.toHexString(st_dev))
            .append(",ino=")
            .append(st_ino)
            .append(')')
        return sb.toString()
    }
}
