package com.gph.fable.shared.shell.am

import com.gph.fable.shared.errors.Errno

class AmSocketServerErrno internal constructor(type: String, code: Int, message: String) : Errno(type, code, message) {
    companion object {
        const val TYPE = "AmSocketServer Error"
        @JvmField
        val ERRNO_PARSE_AM_COMMAND_FAILED_WITH_EXCEPTION =
            Errno(TYPE, 100, "Parse am command `%1\$s` failed.\nException: %2\$s")
        @JvmField
        val ERRNO_RUN_AM_COMMAND_FAILED_WITH_EXCEPTION =
            Errno(TYPE, 101, "Run am command `%1\$s` failed.\nException: %2\$s")
    }
}
