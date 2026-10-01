package com.gph.fable.shared.termux.shell.am

import android.content.Context
import androidx.annotation.Keep
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.net.socket.local.LocalClientSocket
import com.gph.fable.shared.net.socket.local.LocalSocketManager
import com.gph.fable.shared.net.socket.local.LocalSocketManagerClientBase
import com.gph.fable.shared.net.socket.local.LocalSocketRunConfig
import com.gph.fable.shared.shell.am.AmSocketServer
import com.gph.fable.shared.shell.am.AmSocketServerRunConfig
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.crash.FableCrashUtils
import com.gph.fable.shared.termux.plugins.FablePluginUtils
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants
import com.gph.fable.shared.termux.shell.command.environment.FableAppShellEnvironment

object FableAmSocketServer {
    const val LOG_TAG = "FableAmSocketServer"
    const val TITLE = "FableAm"

    @Volatile
    private var fableAmSocketServer: LocalSocketManager? = null

    @Keep
    @JvmField
    var TERMUX_APP_AM_SOCKET_SERVER_ENABLED: Boolean? = null

    @JvmStatic
    fun setupFableAmSocketServer(context: Context) {
        var enabled = false
        if (FableAppSharedProperties.getProperties()?.shouldRunFableAmSocketServer() == true) {
            Logger.logDebug(LOG_TAG, "Starting $TITLE socket server since its enabled")
            start(context)
            if (fableAmSocketServer?.isRunning() == true) {
                enabled = true
                Logger.logDebug(LOG_TAG, "$TITLE socket server successfully started")
            }
        } else {
            Logger.logDebug(LOG_TAG, "Not starting $TITLE socket server since its not enabled")
        }
        TERMUX_APP_AM_SOCKET_SERVER_ENABLED = enabled
        FableAppShellEnvironment.updateFableAppAMSocketServerEnabled(context)
    }

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        stop()
        val config = AmSocketServerRunConfig(
            TITLE,
            TermuxConstants.TERMUX_APP.TERMUX_AM_SOCKET_FILE_PATH,
            FableAmSocketServerClient()
        )
        fableAmSocketServer = AmSocketServer.start(context, config)
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        val server = fableAmSocketServer ?: return
        server.stop()?.let { server.onError(it) }
        fableAmSocketServer = null
    }

    @JvmStatic
    @Synchronized
    fun updateState(context: Context) {
        val properties = FableAppSharedProperties.getProperties()
        if (properties?.shouldRunFableAmSocketServer() == true) {
            if (fableAmSocketServer == null) {
                Logger.logDebug(LOG_TAG, "updateState: Starting $TITLE socket server")
                start(context)
            }
        } else if (fableAmSocketServer != null) {
            Logger.logDebug(LOG_TAG, "updateState: Disabling $TITLE socket server")
            stop()
        }
    }

    @JvmStatic
    @Synchronized
    fun getFableAmSocketServer(): LocalSocketManager? = fableAmSocketServer

    @JvmStatic
    @Synchronized
    fun showErrorNotification(
        context: Context,
        error: Error,
        localSocketRunConfig: LocalSocketRunConfig,
        clientSocket: LocalClientSocket?
    ) {
        FablePluginUtils.sendPluginCommandErrorNotification(
            context,
            LOG_TAG,
            "${localSocketRunConfig.getTitle()} Socket Server Error",
            error.getMinimalErrorString(),
            LocalSocketManager.getErrorMarkdownString(error, localSocketRunConfig, clientSocket)
        )
    }

    @JvmStatic
    fun getFableAppAMSocketServerEnabled(currentPackageContext: Context): Boolean? {
        return if (TermuxConstants.TERMUX_PACKAGE_NAME == currentPackageContext.packageName) {
            TERMUX_APP_AM_SOCKET_SERVER_ENABLED
        } else {
            null
        }
    }

    class FableAmSocketServerClient : AmSocketServer.AmSocketServerClient() {
        override fun getLocalSocketManagerClientThreadUEH(
            localSocketManager: LocalSocketManager
        ): Thread.UncaughtExceptionHandler? = FableCrashUtils.getCrashHandler(localSocketManager.getContext())

        override fun onError(
            localSocketManager: LocalSocketManager,
            clientSocket: LocalClientSocket?,
            error: Error
        ) {
            if (localSocketManager.isRunning()) {
                showErrorNotification(
                    localSocketManager.getContext(),
                    error,
                    localSocketManager.getLocalSocketRunConfig(),
                    clientSocket
                )
            }
            super.onError(localSocketManager, clientSocket, error)
        }

        override fun onDisallowedClientConnected(
            localSocketManager: LocalSocketManager,
            clientSocket: LocalClientSocket,
            error: Error
        ) {
            showErrorNotification(
                localSocketManager.getContext(),
                error,
                localSocketManager.getLocalSocketRunConfig(),
                clientSocket
            )
            super.onDisallowedClientConnected(localSocketManager, clientSocket, error)
        }

        override fun getLogTag(): String = FableAmSocketServerClient.LOG_TAG

        companion object {
            const val LOG_TAG = "FableAmSocketServerClient"
        }
    }
}
