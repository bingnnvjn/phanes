package com.gph.fable.shared.termux.shell.command.environment

import android.content.Context
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.environment.AndroidShellEnvironment
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import com.gph.fable.shared.termux.FableBootstrap
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.shell.FableShellUtils
import java.nio.charset.Charset

class FableShellEnvironment : AndroidShellEnvironment() {
    companion object {
        private const val LOG_TAG = "FableShellEnvironment"
        const val ENV_PREFIX = "PREFIX"

        // @Synchronized：与 Java 原件 public synchronized static 等价；writeEnvironmentToFile 写固定
        // 临时文件 termux.env.tmp 再 move，并发调用需串行化。
        @Synchronized
        @JvmStatic
        fun init(currentPackageContext: Context) {
            FableAppShellEnvironment.setFableAppEnvironment(currentPackageContext)
        }

        @Synchronized
        @JvmStatic
        fun writeEnvironmentToFile(currentPackageContext: Context) {
            val environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(
                FableShellEnvironment().getEnvironment(currentPackageContext, false)
            )
            var error: Error? = FileUtils.writeTextToFile(
                "termux.env.tmp",
                TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
                Charset.defaultCharset(),
                environmentString,
                false
            )
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString())
                return
            }
            error = FileUtils.moveRegularFile(
                "termux.env.tmp",
                TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
                TermuxConstants.TERMUX_ENV_FILE_PATH,
                true
            )
            if (error != null) Logger.logErrorExtended(LOG_TAG, error.toString())
        }
    }

    init {
        shellCommandShellEnvironment = FableShellCommandShellEnvironment()
    }

    override fun getEnvironment(currentPackageContext: Context, isFailSafe: Boolean): HashMap<String, String> {
        val environment = super.getEnvironment(currentPackageContext, isFailSafe)
        FableAppShellEnvironment.getEnvironment(currentPackageContext)?.let(environment::putAll)

        var applicationInfo = PackageUtils.getApplicationInfoForPackage(currentPackageContext, TermuxConstants.TERMUX_PACKAGE_NAME)
        if (applicationInfo != null && !applicationInfo.enabled) applicationInfo = null
        applicationInfo?.let { environment["TERMUX__APPS_DIR"] = "${it.dataDir}/termux/apps" }
        environment["TERMUX__ROOTFS_DIR"] = TermuxConstants.TERMUX_FILES_DIR_PATH
        environment[ENV_HOME] = TermuxConstants.TERMUX_HOME_DIR_PATH
        environment["TERMUX__HOME"] = TermuxConstants.TERMUX_HOME_DIR_PATH
        environment[ENV_PREFIX] = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        environment["TERMUX__PREFIX"] = TermuxConstants.TERMUX_PREFIX_DIR_PATH

        if (!isFailSafe) {
            environment[ENV_TMPDIR] = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH
            if (FableBootstrap.isAppPackageVariantAPTAndroid5()) {
                environment[ENV_PATH] = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}:${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/applets"
                environment[ENV_LD_LIBRARY_PATH] = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH
            } else {
                environment[ENV_PATH] = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                environment.remove(ENV_LD_LIBRARY_PATH)
            }
        }
        return environment
    }

    override fun getDefaultWorkingDirectoryPath() = TermuxConstants.TERMUX_HOME_DIR_PATH
    override fun getDefaultBinPath() = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    override fun setupShellCommandArguments(executable: String, arguments: Array<String>?) =
        FableShellUtils.setupShellCommandArguments(executable, arguments)
}
