package com.gph.fable.shared.shell.am

import android.Manifest
import android.app.Application
import android.content.Context
import com.gph.fable.shared.R
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.android.PermissionUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.net.socket.local.LocalClientSocket
import com.gph.fable.shared.net.socket.local.LocalSocketManager
import com.gph.fable.shared.net.socket.local.LocalSocketManagerClientBase
import com.gph.fable.shared.net.socket.local.LocalSocketRunConfig
import com.gph.fable.shared.shell.ArgumentTokenizer
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.termux.am.Am
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.Arrays

object AmSocketServer {
    const val LOG_TAG = "AmSocketServer"

    @JvmStatic
    @Synchronized
    fun start(context: Context, localSocketRunConfig: LocalSocketRunConfig): LocalSocketManager? {
        val manager = LocalSocketManager(context, localSocketRunConfig)
        val error = manager.start()
        if (error != null) {
            manager.onError(error)
            return null
        }
        return manager
    }

    @JvmStatic
    fun processAmClient(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket) {
        var error: Error?
        val data = StringBuilder()
        error = clientSocket.readDataOnInputStream(data, true)
        if (error != null) {
            sendResultToClient(localSocketManager, clientSocket, 1, null, error.toString())
            return
        }

        val commandString = data.toString()
        Logger.logVerbose(
            LOG_TAG,
            "am command received from peer ${clientSocket.getPeerCred().getMinimalString()}\nam command: `$commandString`"
        )

        val commandList = ArrayList<String>()
        error = parseAmCommand(commandString, commandList)
        if (error != null) {
            sendResultToClient(localSocketManager, clientSocket, 1, null, error.toString())
            return
        }

        val commandArray = commandList.toTypedArray()
        Logger.logDebug(
            LOG_TAG,
            "am command received from peer ${clientSocket.getPeerCred().getMinimalString()}\n" +
                ExecutionCommand.getArgumentsLogString("am command", commandArray)
        )

        val config = localSocketManager.getLocalSocketRunConfig() as AmSocketServerRunConfig
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        error = runAmCommand(
            localSocketManager.getContext(),
            commandArray,
            stdout,
            stderr,
            config.shouldCheckDisplayOverAppsPermission()
        )
        if (error != null) {
            sendResultToClient(
                localSocketManager,
                clientSocket,
                1,
                stdout.toString(),
                if (stderr.isNotEmpty()) "$stderr\n\n$error" else error.toString()
            )
        }
        sendResultToClient(localSocketManager, clientSocket, 0, stdout.toString(), stderr.toString())
    }

    @JvmStatic
    fun sendResultToClient(
        localSocketManager: LocalSocketManager,
        clientSocket: LocalClientSocket,
        exitCode: Int,
        stdout: String?,
        stderr: String?
    ) {
        val result = buildString {
            append(sanitizeExitCode(clientSocket, exitCode))
            append('\u0000')
            append(stdout ?: "")
            append('\u0000')
            append(stderr ?: "")
        }
        clientSocket.sendDataToOutputStream(result, true)?.let { localSocketManager.onError(clientSocket, it) }
    }

    @JvmStatic
    fun sanitizeExitCode(clientSocket: LocalClientSocket, exitCode: Int): Int {
        if (exitCode < 0 || exitCode > 255) {
            Logger.logWarn(
                LOG_TAG,
                "Ignoring invalid peer ${clientSocket.getPeerCred().getMinimalString()} result value \"$exitCode\" and force setting it to \"1\""
            )
            return 1
        }
        return exitCode
    }

    @JvmStatic
    fun parseAmCommand(amCommandString: String?, amCommandList: MutableList<String>): Error? {
        if (amCommandString.isNullOrEmpty()) return null
        return try {
            amCommandList.addAll(ArgumentTokenizer.tokenize(amCommandString))
            null
        } catch (e: Exception) {
            AmSocketServerErrno.ERRNO_PARSE_AM_COMMAND_FAILED_WITH_EXCEPTION
                .getError(e, amCommandString, e.message)
        }
    }

    @JvmStatic
    fun runAmCommand(
        context: Context,
        amCommandArray: Array<String>,
        stdout: StringBuilder,
        stderr: StringBuilder,
        checkDisplayOverAppsPermission: Boolean
    ): Error? {
        return try {
            ByteArrayOutputStream().use { stdoutBytes ->
                PrintStream(stdoutBytes).use { stdoutStream ->
                    ByteArrayOutputStream().use { stderrBytes ->
                        PrintStream(stderrBytes).use { stderrStream ->
                            if (checkDisplayOverAppsPermission &&
                                amCommandArray.isNotEmpty() &&
                                (amCommandArray[0] == "start" || amCommandArray[0] == "startservice") &&
                                !PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(context, true)
                            ) {
                                throw IllegalStateException(
                                    context.getString(
                                        R.string.error_display_over_other_apps_permission_not_granted,
                                        PackageUtils.getAppNameForPackage(context)
                                    )
                                )
                            }
                            Am(
                                stdoutStream,
                                stderrStream,
                                context.applicationContext as Application
                            ).run(amCommandArray)
                            stdoutStream.flush()
                            stdout.append(stdoutBytes.toString(StandardCharsets.UTF_8.name()))
                            stderrStream.flush()
                            stderr.append(stderrBytes.toString(StandardCharsets.UTF_8.name()))
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            AmSocketServerErrno.ERRNO_RUN_AM_COMMAND_FAILED_WITH_EXCEPTION
                .getError(e, Arrays.toString(amCommandArray), e.message)
        }
    }

    abstract class AmSocketServerClient : LocalSocketManagerClientBase() {
        override fun onClientAccepted(
            localSocketManager: LocalSocketManager,
            clientSocket: LocalClientSocket
        ) {
            processAmClient(localSocketManager, clientSocket)
            super.onClientAccepted(localSocketManager, clientSocket)
        }
    }
}
