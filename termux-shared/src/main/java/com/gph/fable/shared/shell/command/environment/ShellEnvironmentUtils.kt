package com.gph.fable.shared.shell.command.environment

import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger

object ShellEnvironmentUtils {
    private const val LOG_TAG = "ShellEnvironmentUtils"

    @JvmStatic
    fun convertEnvironmentToEnviron(environmentMap: HashMap<String, String>): List<String> =
        environmentMap.mapNotNull { (name, value) ->
            if (isValidEnvironmentVariableNameValuePair(name, value, true)) "$name=$value" else null
        }

    @JvmStatic
    fun convertEnvironmentToDotEnvFile(environmentMap: HashMap<String, String>): String =
        convertEnvironmentToDotEnvFile(convertEnvironmentMapToEnvironmentVariableList(environmentMap))

    @JvmStatic
    fun convertEnvironmentToDotEnvFile(environmentList: List<ShellEnvironmentVariable>): String {
        val output = StringBuilder()
        environmentList.sortedWith(compareBy<ShellEnvironmentVariable> { it.name }).forEach { variable ->
            if (isValidEnvironmentVariableNameValuePair(variable.name, variable.value, true) && variable.value != null) {
                val value = if (variable.escaped) variable.value!! else variable.value!!.replace(Regex("([\"`\\\\$])"), "\\\\$1")
                output.append("export ").append(variable.name).append("=\"").append(value).append("\"\n")
            }
        }
        return output.toString()
    }

    @JvmStatic
    fun convertEnvironmentMapToEnvironmentVariableList(environmentMap: HashMap<String, String>): List<ShellEnvironmentVariable> =
        environmentMap.map { (name, value) -> ShellEnvironmentVariable(name, value) }

    @JvmStatic
    fun isValidEnvironmentVariableNameValuePair(name: String?, value: String?, logErrors: Boolean): Boolean {
        if (!isValidEnvironmentVariableName(name)) {
            if (logErrors) Logger.logErrorPrivate(LOG_TAG, "Invalid environment variable name. name=`$name`, value=`$value`")
            return false
        }
        if (!isValidEnvironmentVariableValue(value)) {
            if (logErrors) Logger.logErrorPrivate(LOG_TAG, "Invalid environment variable value. name=`$name`, value=`$value`")
            return false
        }
        return true
    }

    @JvmStatic
    fun isValidEnvironmentVariableName(name: String?): Boolean =
        name != null && !name.contains('\u0000') && Regex("[a-zA-Z_][a-zA-Z0-9_]*").matches(name)

    @JvmStatic
    fun isValidEnvironmentVariableValue(value: String?): Boolean =
        value != null && !value.contains('\u0000')

    @JvmStatic
    fun putToEnvIfInSystemEnv(environment: HashMap<String, String>, name: String) {
        System.getenv(name)?.let { environment[name] = it }
    }

    @JvmStatic
    fun putToEnvIfSet(environment: HashMap<String, String>, name: String, value: String?) {
        if (value != null) environment[name] = value
    }

    @JvmStatic
    fun putToEnvIfSet(environment: HashMap<String, String>, name: String, value: Boolean?) {
        if (value != null) environment[name] = value.toString()
    }

    @JvmStatic
    fun createHomeDir(environment: HashMap<String, String>) {
        val homeDirectory = environment[UnixShellEnvironment.ENV_HOME]
        if (!homeDirectory.isNullOrEmpty()) {
            val error: Error? = FileUtils.createDirectoryFile("shell home", homeDirectory)
            if (error != null) Logger.logErrorExtended(LOG_TAG, "Failed to create shell home directory\n$error")
        }
    }
}
