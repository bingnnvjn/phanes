package com.gph.fable.shared.file.filesystem

import android.system.OsConstants

/**
 * Android-changed: Use constants from android.system.OsConstants.
 */
object UnixConstants {

    @JvmField
    internal val O_RDONLY: Int = OsConstants.O_RDONLY

    @JvmField
    internal val O_WRONLY: Int = OsConstants.O_WRONLY

    @JvmField
    internal val O_RDWR: Int = OsConstants.O_RDWR

    @JvmField
    internal val O_APPEND: Int = OsConstants.O_APPEND

    @JvmField
    internal val O_CREAT: Int = OsConstants.O_CREAT

    @JvmField
    internal val O_EXCL: Int = OsConstants.O_EXCL

    @JvmField
    internal val O_TRUNC: Int = OsConstants.O_TRUNC

    @JvmField
    internal val O_SYNC: Int = OsConstants.O_SYNC

    // Crash on Android 5.
    // No static field O_DSYNC of type I in class Landroid/system/OsConstants; or its superclasses
    //@RequiresApi(Build.VERSION_CODES.O_MR1)
    //internal val O_DSYNC: Int = OsConstants.O_DSYNC

    @JvmField
    internal val O_NOFOLLOW: Int = OsConstants.O_NOFOLLOW

    @JvmField
    internal val S_IAMB: Int = get_S_IAMB()

    @JvmField
    internal val S_IRUSR: Int = OsConstants.S_IRUSR

    @JvmField
    internal val S_IWUSR: Int = OsConstants.S_IWUSR

    @JvmField
    internal val S_IXUSR: Int = OsConstants.S_IXUSR

    @JvmField
    internal val S_IRGRP: Int = OsConstants.S_IRGRP

    @JvmField
    internal val S_IWGRP: Int = OsConstants.S_IWGRP

    @JvmField
    internal val S_IXGRP: Int = OsConstants.S_IXGRP

    @JvmField
    internal val S_IROTH: Int = OsConstants.S_IROTH

    @JvmField
    internal val S_IWOTH: Int = OsConstants.S_IWOTH

    @JvmField
    internal val S_IXOTH: Int = OsConstants.S_IXOTH

    @JvmField
    internal val S_IFMT: Int = OsConstants.S_IFMT

    @JvmField
    internal val S_IFREG: Int = OsConstants.S_IFREG

    @JvmField
    internal val S_IFDIR: Int = OsConstants.S_IFDIR

    @JvmField
    internal val S_IFLNK: Int = OsConstants.S_IFLNK

    @JvmField
    internal val S_IFSOCK: Int = OsConstants.S_IFSOCK

    @JvmField
    internal val S_IFCHR: Int = OsConstants.S_IFCHR

    @JvmField
    internal val S_IFBLK: Int = OsConstants.S_IFBLK

    @JvmField
    internal val S_IFIFO: Int = OsConstants.S_IFIFO

    @JvmField
    internal val R_OK: Int = OsConstants.R_OK

    @JvmField
    internal val W_OK: Int = OsConstants.W_OK

    @JvmField
    internal val X_OK: Int = OsConstants.X_OK

    @JvmField
    internal val F_OK: Int = OsConstants.F_OK

    @JvmField
    internal val ENOENT: Int = OsConstants.ENOENT

    @JvmField
    internal val EACCES: Int = OsConstants.EACCES

    @JvmField
    internal val EEXIST: Int = OsConstants.EEXIST

    @JvmField
    internal val ENOTDIR: Int = OsConstants.ENOTDIR

    @JvmField
    internal val EINVAL: Int = OsConstants.EINVAL

    @JvmField
    internal val EXDEV: Int = OsConstants.EXDEV

    @JvmField
    internal val EISDIR: Int = OsConstants.EISDIR

    @JvmField
    internal val ENOTEMPTY: Int = OsConstants.ENOTEMPTY

    @JvmField
    internal val ENOSPC: Int = OsConstants.ENOSPC

    @JvmField
    internal val EAGAIN: Int = OsConstants.EAGAIN

    @JvmField
    internal val ENOSYS: Int = OsConstants.ENOSYS

    @JvmField
    internal val ELOOP: Int = OsConstants.ELOOP

    @JvmField
    internal val EROFS: Int = OsConstants.EROFS

    @JvmField
    internal val ENODATA: Int = OsConstants.ENODATA

    @JvmField
    internal val ERANGE: Int = OsConstants.ERANGE

    @JvmField
    internal val EMFILE: Int = OsConstants.EMFILE

    // S_IAMB are access mode bits, therefore, calculated by taking OR of all the read, write and
    // execute permissions bits for owner, group and other.
    private fun get_S_IAMB(): Int {
        return (OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR or
            OsConstants.S_IRGRP or OsConstants.S_IWGRP or OsConstants.S_IXGRP or
            OsConstants.S_IROTH or OsConstants.S_IWOTH or OsConstants.S_IXOTH)
    }

    @JvmField
    internal val AT_SYMLINK_NOFOLLOW = 0x100

    @JvmField
    internal val AT_REMOVEDIR = 0x200
}
