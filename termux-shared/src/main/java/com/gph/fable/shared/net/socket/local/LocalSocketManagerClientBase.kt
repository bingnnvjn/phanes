package com.gph.fable.shared.net.socket.local

import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.logger.Logger

abstract class LocalSocketManagerClientBase : ILocalSocketManager {
    override fun getLocalSocketManagerClientThreadUEH(localSocketManager: LocalSocketManager): Thread.UncaughtExceptionHandler? = null
    override fun onError(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket?, error: Error) {
        Logger.logErrorPrivate(getLogTag(), "onError")
        Logger.logErrorPrivateExtended(getLogTag(), LocalSocketManager.getErrorLogString(error, localSocketManager.getLocalSocketRunConfig(), clientSocket))
    }
    override fun onDisallowedClientConnected(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket, error: Error) {
        Logger.logWarn(getLogTag(), "onDisallowedClientConnected")
        Logger.logWarnExtended(getLogTag(), LocalSocketManager.getErrorLogString(error, localSocketManager.getLocalSocketRunConfig(), clientSocket))
    }
    override fun onClientAccepted(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket) {
        clientSocket.closeClientSocket(true)
    }
    protected abstract fun getLogTag(): String
}
