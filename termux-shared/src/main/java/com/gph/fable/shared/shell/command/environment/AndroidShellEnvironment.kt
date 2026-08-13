package com.gph.fable.shared.shell.command.environment

import android.content.Context
import android.os.Build
import com.gph.fable.shared.shell.command.ExecutionCommand
import java.io.File

open class AndroidShellEnvironment : UnixShellEnvironment() {
    companion object {
        const val ANDROID_ENV_SCOPE = "ANDROID__"
        const val ENV_ANDROID__BUILD_VERSION_SDK = "${ANDROID_ENV_SCOPE}BUILD_VERSION_SDK"
    }

    @JvmField
    protected var shellCommandShellEnvironment: ShellCommandShellEnvironment = ShellCommandShellEnvironment()

    override fun getEnvironment(currentPackageContext: Context, isFailSafe: Boolean): HashMap<String, String> {
        val environment = hashMapOf(
            ENV_HOME to "/",
            ENV_LANG to "en_US.UTF-8",
            ENV_PATH to System.getenv(ENV_PATH),
            ENV_TMPDIR to "/data/local/tmp",
            ENV_COLORTERM to "truecolor",
            ENV_TERM to "xterm-256color"
        )
        listOf(
            "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
            "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT", "LOOP_MOUNTPOINT",
            "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH"
        ).forEach { ShellEnvironmentUtils.putToEnvIfInSystemEnv(environment, it) }
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_ANDROID__BUILD_VERSION_SDK, Build.VERSION.SDK_INT.toString())
        return environment
    }

    override fun getDefaultWorkingDirectoryPath(): String = "/"
    override fun getDefaultBinPath(): String = "/system/bin"

    override fun setupShellCommandEnvironment(
        currentPackageContext: Context,
        executionCommand: ExecutionCommand
    ): HashMap<String, String> {
        val environment = getEnvironment(currentPackageContext, executionCommand.isFailsafe)
        environment[ENV_PWD] = if (!executionCommand.workingDirectory.isNullOrEmpty()) {
            File(executionCommand.workingDirectory!!).absolutePath
        } else getDefaultWorkingDirectoryPath()
        ShellEnvironmentUtils.createHomeDir(environment)
        if (executionCommand.setShellCommandShellEnvironment) {
            environment.putAll(shellCommandShellEnvironment.getEnvironment(currentPackageContext, executionCommand))
        }
        return environment
    }
}
