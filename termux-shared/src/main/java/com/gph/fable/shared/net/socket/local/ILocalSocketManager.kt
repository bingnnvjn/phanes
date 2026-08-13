package com.gph.fable.shared.net.socket.local

import com.gph.fable.shared.errors.Error

interface ILocalSocketManager {
    fun getLocalSocketManagerClientThreadUEH(localSocketManager: LocalSocketManager): Thread.UncaughtExceptionHandler?
    fun onError(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket?, error: Error)
    fun onDisallowedClientConnected(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket, error: Error)
    fun onClientAccepted(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket)
}
