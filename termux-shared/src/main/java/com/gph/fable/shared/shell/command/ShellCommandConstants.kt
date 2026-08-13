package com.gph.fable.shared.shell.command

import com.gph.fable.shared.errors.Errno

/** Constants shared by shell command execution and result delivery. */
class ShellCommandConstants private constructor() {
    class RESULT_SENDER private constructor() {
        companion object {
            @JvmField
            val FORMAT_SUCCESS_STDOUT = "%1\$s%n"
            @JvmField
            val FORMAT_SUCCESS_STDOUT__EXIT_CODE = "%1\$s%n%n%n%nexit_code=%2\$s%n"
            @JvmField
            val FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE =
                "stdout=%n%1\$s%n%n%n%nstderr=%n%2\$s%n%n%n%nexit_code=%3\$s%n"
            @JvmField
            val FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE =
                "err=%1\$s%n%n%n%nerrmsg=%n%2\$s%n%n%n%nstdout=%n%3\$s%n%n%n%nstderr=%n%4\$s%n%n%n%nexit_code=%5\$s%n"

            @JvmField
            val RESULT_FILE_ERR_PREFIX = "err"
            @JvmField
            val RESULT_FILE_ERRMSG_PREFIX = "errmsg"
            @JvmField
            val RESULT_FILE_STDOUT_PREFIX = "stdout"
            @JvmField
            val RESULT_FILE_STDERR_PREFIX = "stderr"
            @JvmField
            val RESULT_FILE_EXIT_CODE_PREFIX = "exit_code"
        }
    }
}
