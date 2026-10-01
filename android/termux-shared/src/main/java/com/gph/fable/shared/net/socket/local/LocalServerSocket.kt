package com.gph.fable.shared.net.socket.local

import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.jni.models.JniResult
import com.gph.fable.shared.logger.Logger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** The server socket for [LocalSocketManager]. */
open class LocalServerSocket(
    @JvmField protected val mLocalSocketManager: LocalSocketManager
) : Closeable {
    @JvmField val mLocalSocketRunConfig = mLocalSocketManager.getLocalSocketRunConfig()
    @JvmField val mLocalSocketManagerClient = mLocalSocketRunConfig.getLocalSocketManagerClient()
    @JvmField val mClientSocketListener: Thread = Thread(ClientSocketListener())

    @JvmOverloads
    fun start(): Error? {
        Logger.logDebug(LOG_TAG, "start")
        var path = mLocalSocketRunConfig.getPath()
        if (path.isNullOrEmpty()) return LocalSocketErrno.ERRNO_SERVER_SOCKET_PATH_NULL_OR_EMPTY.getError(mLocalSocketRunConfig.getTitle())
        if (!mLocalSocketRunConfig.isAbstractNamespaceSocket()) path = FileUtils.getCanonicalPath(path, null)
        if (path.toByteArray(StandardCharsets.UTF_8).size > 108)
            return LocalSocketErrno.ERRNO_SERVER_SOCKET_PATH_TOO_LONG.getError(mLocalSocketRunConfig.getTitle(), path)
        val backlog = mLocalSocketRunConfig.getBacklog()
        if (backlog <= 0) return LocalSocketErrno.ERRNO_SERVER_SOCKET_BACKLOG_INVALID.getError(mLocalSocketRunConfig.getTitle(), backlog)
        if (!mLocalSocketRunConfig.isAbstractNamespaceSocket()) {
            if (!path.startsWith("/")) return LocalSocketErrno.ERRNO_SERVER_SOCKET_PATH_NOT_ABSOLUTE.getError(mLocalSocketRunConfig.getTitle(), path)
            val parent = File(path).parent
            val error = FileUtils.validateDirectoryFileExistenceAndPermissions(
                mLocalSocketRunConfig.getTitle() + " server socket file parent",
                parent, null, true, SERVER_SOCKET_PARENT_DIRECTORY_PERMISSIONS, true, true, false, false
            )
            if (error != null) return error
            deleteServerSocketFile()?.let { return it }
        }
        val result = LocalSocketManager.createServerSocket(
            mLocalSocketRunConfig.getLogTitle() + " (server)", path.toByteArray(StandardCharsets.UTF_8), backlog
        )
        if (result == null || result.retval != 0)
            return LocalSocketErrno.ERRNO_CREATE_SERVER_SOCKET_FAILED.getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result))
        val fd = result.intData
        if (fd < 0) return LocalSocketErrno.ERRNO_SERVER_SOCKET_FD_INVALID.getError(fd, mLocalSocketRunConfig.getTitle())
        mLocalSocketRunConfig.setFD(fd)
        mClientSocketListener.uncaughtExceptionHandler = mLocalSocketManager.getLocalSocketManagerClientThreadUEH()
        try { mClientSocketListener.start() } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "mClientSocketListener start failed", e)
        }
        return null
    }

    fun stop(): Error? {
        Logger.logDebug(LOG_TAG, "stop")
        try { mClientSocketListener.interrupt() } catch (_: Exception) {}
        closeServerSocket(false)?.let { return it }
        return deleteServerSocketFile()
    }

    fun closeServerSocket(logErrorMessage: Boolean): Error? {
        return try {
            close()
            null
        } catch (e: IOException) {
            val error = LocalSocketErrno.ERRNO_CLOSE_SERVER_SOCKET_FAILED_WITH_EXCEPTION
                .getError(e, mLocalSocketRunConfig.getTitle(), e.message)
            if (logErrorMessage) Logger.logErrorExtended(LOG_TAG, error.getErrorLogString())
            error
        }
    }

    override fun close() {
        val fd = mLocalSocketRunConfig.getFD()
        if (fd >= 0) {
            val result = LocalSocketManager.closeSocket(mLocalSocketRunConfig.getLogTitle() + " (server)", fd)
            if (result == null || result.retval != 0) throw IOException(JniResult.getErrorString(result))
            mLocalSocketRunConfig.setFD(-1)
        }
    }

    private fun deleteServerSocketFile(): Error? =
        if (!mLocalSocketRunConfig.isAbstractNamespaceSocket())
            FileUtils.deleteSocketFile(mLocalSocketRunConfig.getTitle() + " server socket file", mLocalSocketRunConfig.getPath(), true)
        else null

    fun accept(): LocalClientSocket? {
        while (true) {
            val fd = mLocalSocketRunConfig.getFD()
            if (fd < 0) return null
            var result = LocalSocketManager.accept(mLocalSocketRunConfig.getLogTitle() + " (client)", fd)
            if (result == null || result.retval != 0) {
                mLocalSocketManager.onError(LocalSocketErrno.ERRNO_ACCEPT_CLIENT_SOCKET_FAILED.getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result)))
                continue
            }
            val clientFd = result.intData
            if (clientFd < 0) {
                mLocalSocketManager.onError(LocalSocketErrno.ERRNO_CLIENT_SOCKET_FD_INVALID.getError(clientFd, mLocalSocketRunConfig.getTitle()))
                continue
            }
            val peerCred = PeerCred()
            result = LocalSocketManager.getPeerCred(mLocalSocketRunConfig.getLogTitle() + " (client)", clientFd, peerCred)
            if (result == null || result.retval != 0) {
                mLocalSocketManager.onError(LocalSocketErrno.ERRNO_GET_CLIENT_SOCKET_PEER_UID_FAILED.getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result)))
                LocalClientSocket.closeClientSocket(mLocalSocketManager, clientFd)
                continue
            }
            val peerUid = peerCred.uid
            if (peerUid < 0) {
                mLocalSocketManager.onError(LocalSocketErrno.ERRNO_CLIENT_SOCKET_PEER_UID_INVALID.getError(peerUid, mLocalSocketRunConfig.getTitle()))
                LocalClientSocket.closeClientSocket(mLocalSocketManager, clientFd)
                continue
            }
            val clientSocket = LocalClientSocket(mLocalSocketManager, clientFd, peerCred)
            Logger.logVerbose(LOG_TAG, "Client socket accept for \"${mLocalSocketRunConfig.getTitle()}\" server\n${clientSocket.getLogString()}")
            val appUid = mLocalSocketManager.getContext().applicationInfo.uid
            if (peerUid != appUid && peerUid != 0) {
                mLocalSocketManager.onDisallowedClientConnected(
                    clientSocket,
                    LocalSocketErrno.ERRNO_CLIENT_SOCKET_PEER_UID_DISALLOWED.getError(
                        clientSocket.getPeerCred().getMinimalString(), mLocalSocketRunConfig.getTitle()
                    )
                )
                clientSocket.closeClientSocket(true)
                continue
            }
            return clientSocket
        }
    }

    protected inner class ClientSocketListener : Runnable {
        override fun run() {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    var clientSocket: LocalClientSocket? = null
                    try {
                        clientSocket = accept() ?: break
                        var error = clientSocket.setReadTimeout()
                        if (error != null) {
                            mLocalSocketManager.onError(clientSocket, error)
                            clientSocket.closeClientSocket(true)
                            continue
                        }
                        error = clientSocket.setWriteTimeout()
                        if (error != null) {
                            mLocalSocketManager.onError(clientSocket, error)
                            clientSocket.closeClientSocket(true)
                            continue
                        }
                        mLocalSocketManager.onClientAccepted(clientSocket)
                    } catch (t: Throwable) {
                        mLocalSocketManager.onError(clientSocket, LocalSocketErrno.ERRNO_CLIENT_SOCKET_LISTENER_FAILED_WITH_EXCEPTION.getError(t, mLocalSocketRunConfig.getTitle(), t.message))
                        clientSocket?.closeClientSocket(true)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { close() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val LOG_TAG = "LocalServerSocket"
        const val SERVER_SOCKET_PARENT_DIRECTORY_PERMISSIONS = "rwx"
    }
}
