package com.gph.fable.shared.activity

import com.gph.fable.shared.errors.Errno

class ActivityErrno internal constructor(type: String, code: Int, message: String) : Errno(type, code, message) {

    companion object {
        const val TYPE = "Activity Error"

        /* Errors for starting activities (100-150) */
        @JvmField
        val ERRNO_START_ACTIVITY_FAILED_WITH_EXCEPTION = Errno(TYPE, 100, "Failed to start \"%1\$s\" activity.\nException: %2\$s")
        @JvmField
        val ERRNO_START_ACTIVITY_FOR_RESULT_FAILED_WITH_EXCEPTION = Errno(TYPE, 101, "Failed to start \"%1\$s\" activity for result.\nException: %2\$s")
        @JvmField
        val ERRNO_STARTING_ACTIVITY_WITH_NULL_CONTEXT = Errno(TYPE, 102, "Cannot start \"%1\$s\" activity with null Context")
    }
}
