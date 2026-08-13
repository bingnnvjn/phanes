package com.gph.fable.shared.net.socket.local

import android.content.Context
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.jni.models.JniResult
import com.gph.fable.shared.logger.Logger

open class LocalSocketManager(
    context: Context,
    @JvmField protected val mLocalSocketRunConfig: LocalSocketRunConfig
) {
    companion object {
        const val LOG_TAG = "LocalSocketManager"
        @JvmField protected var LOCAL_SOCKET_LIBRARY = "local-socket"
        @JvmField protected var localSocketLibraryLoaded = false

        @JvmStatic
        fun createServerSocket(serverTitle: String, path: ByteArray, backlog: Int): JniResult? =
            try { createServerSocketNative(serverTitle, path, backlog) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in createServerSocketNative()", t); JniResult("Exception in createServerSocketNative()", t) }
        @JvmStatic
        fun closeSocket(serverTitle: String, fd: Int): JniResult? =
            try { closeSocketNative(serverTitle, fd) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in closeSocketNative()", t); JniResult("Exception in closeSocketNative()", t) }
        @JvmStatic
        fun accept(serverTitle: String, fd: Int): JniResult? =
            try { acceptNative(serverTitle, fd) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in acceptNative()", t); JniResult("Exception in acceptNative()", t) }
        @JvmStatic
        fun read(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult? =
            try { readNative(serverTitle, fd, data, deadline) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in readNative()", t); JniResult("Exception in readNative()", t) }
        @JvmStatic
        fun send(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult? =
            try { sendNative(serverTitle, fd, data, deadline) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in sendNative()", t); JniResult("Exception in sendNative()", t) }
        @JvmStatic
        fun available(serverTitle: String, fd: Int): JniResult? =
            try { availableNative(serverTitle, fd) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in availableNative()", t); JniResult("Exception in availableNative()", t) }
        @JvmStatic
        fun setSocketReadTimeout(serverTitle: String, fd: Int, timeout: Int): JniResult? =
            try { setSocketReadTimeoutNative(serverTitle, fd, timeout) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in setSocketReadTimeoutNative()", t); JniResult("Exception in setSocketReadTimeoutNative()", t) }
        @JvmStatic
        fun setSocketSendTimeout(serverTitle: String, fd: Int, timeout: Int): JniResult? =
            try { setSocketSendTimeoutNative(serverTitle, fd, timeout) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in setSocketSendTimeoutNative()", t); JniResult("Exception in setSocketSendTimeoutNative()", t) }
        @JvmStatic
        fun getPeerCred(serverTitle: String, fd: Int, peerCred: PeerCred): JniResult? =
            try { getPeerCredNative(serverTitle, fd, peerCred) }
            catch (t: Throwable) { Logger.logStackTraceWithMessage(LOG_TAG, "Exception in getPeerCredNative()", t); JniResult("Exception in getPeerCredNative()", t) }

        @JvmStatic private external fun createServerSocketNative(serverTitle: String, path: ByteArray, backlog: Int): JniResult?
        @JvmStatic private external fun closeSocketNative(serverTitle: String, fd: Int): JniResult?
        @JvmStatic private external fun acceptNative(serverTitle: String, fd: Int): JniResult?
        @JvmStatic private external fun readNative(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult?
        @JvmStatic private external fun sendNative(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult?
        @JvmStatic private external fun availableNative(serverTitle: String, fd: Int): JniResult?
        @JvmStatic private external fun setSocketReadTimeoutNative(serverTitle: String, fd: Int, timeout: Int): JniResult?
        @JvmStatic private external fun setSocketSendTimeoutNative(serverTitle: String, fd: Int, timeout: Int): JniResult?
        @JvmStatic private external fun getPeerCredNative(serverTitle: String, fd: Int, peerCred: PeerCred): JniResult?
        @JvmStatic
        fun getErrorLogString(error: Error, config: LocalSocketRunConfig, clientSocket: LocalClientSocket?): String =
            buildString {
                append(config.getTitle()).append(" Socket Server Error:\n")
                append(error.getErrorLogString()).append("\n\n\n").append(config.getLogString())
                if (clientSocket != null) append("\n\n\n").append(clientSocket.getLogString())
            }
        @JvmStatic
        fun getErrorMarkdownString(error: Error, config: LocalSocketRunConfig, clientSocket: LocalClientSocket?): String =
            buildString {
                append(error.getErrorMarkdownString()).append("\n##\n\n\n").append(config.getMarkdownString())
                if (clientSocket != null) append("\n\n\n").append(clientSocket.getMarkdownString())
            }
    }

    @JvmField protected val mContext: Context = context.applicationContext
    @JvmField protected val mServerSocket: LocalServerSocket = LocalServerSocket(this)
    @JvmField protected val mLocalSocketManagerClient: ILocalSocketManager = mLocalSocketRunConfig.getLocalSocketManagerClient()
    @JvmField protected val mLocalSocketManagerClientThreadUEH: Thread.UncaughtExceptionHandler =
        getLocalSocketManagerClientThreadUEHOrDefault()
    @JvmField protected var mIsRunning = false

    @Synchronized
    fun start(): Error? {
        Logger.logDebugExtended(LOG_TAG, "start\n$mLocalSocketRunConfig")
        if (!localSocketLibraryLoaded) {
            try {
                Logger.logDebug(LOG_TAG, "Loading \"$LOCAL_SOCKET_LIBRARY\" library")
                System.loadLibrary(LOCAL_SOCKET_LIBRARY)
                localSocketLibraryLoaded = true
            } catch (t: Throwable) {
                val error = LocalSocketErrno.ERRNO_START_LOCAL_SOCKET_LIB_LOAD_FAILED_WITH_EXCEPTION
                    .getError(t, LOCAL_SOCKET_LIBRARY, t.message)
                Logger.logErrorExtended(LOG_TAG, error.getErrorLogString())
                return error
            }
        }
        mIsRunning = true
        return mServerSocket.start()
    }

    @Synchronized
    fun stop(): Error? {
        if (!mIsRunning) return null
        Logger.logDebugExtended(LOG_TAG, "stop\n$mLocalSocketRunConfig")
        mIsRunning = false
        return mServerSocket.stop()
    }

    fun onError(error: Error) = onError(null, error)
    fun onError(clientSocket: LocalClientSocket?, error: Error) =
        startLocalSocketManagerClientThread { mLocalSocketManagerClient.onError(this, clientSocket, error) }
    fun onDisallowedClientConnected(clientSocket: LocalClientSocket, error: Error) =
        startLocalSocketManagerClientThread { mLocalSocketManagerClient.onDisallowedClientConnected(this, clientSocket, error) }
    fun onClientAccepted(clientSocket: LocalClientSocket) =
        startLocalSocketManagerClientThread { mLocalSocketManagerClient.onClientAccepted(this, clientSocket) }

    fun startLocalSocketManagerClientThread(runnable: Runnable) {
        val thread = Thread(runnable)
        thread.uncaughtExceptionHandler = mLocalSocketManagerClientThreadUEH
        try { thread.start() }
        catch (e: Exception) { Logger.logStackTraceWithMessage(LOG_TAG, "LocalSocketManagerClientThread start failed", e) }
    }

    fun getContext() = mContext
    fun getLocalSocketRunConfig() = mLocalSocketRunConfig
    fun getLocalSocketManagerClient() = mLocalSocketManagerClient
    fun getServerSocket() = mServerSocket
    fun getLocalSocketManagerClientThreadUEH() = mLocalSocketManagerClientThreadUEH
    protected fun getLocalSocketManagerClientThreadUEHOrDefault(): Thread.UncaughtExceptionHandler =
        mLocalSocketManagerClient.getLocalSocketManagerClientThreadUEH(this)
            ?: Thread.UncaughtExceptionHandler { thread, throwable ->
                Logger.logStackTraceWithMessage(LOG_TAG, "Uncaught exception for $thread in ${mLocalSocketRunConfig.getTitle()} server", throwable)
            }
    fun isRunning() = mIsRunning

}
