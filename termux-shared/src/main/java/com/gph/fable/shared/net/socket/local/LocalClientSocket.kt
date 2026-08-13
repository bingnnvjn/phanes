package com.gph.fable.shared.net.socket.local

import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.jni.models.JniResult
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/** The client socket for [LocalSocketManager]. */
open class LocalClientSocket(
    @JvmField protected val mLocalSocketManager: LocalSocketManager,
    fd: Int,
    @JvmField protected val mPeerCred: PeerCred
) : Closeable {
    @JvmField
    val mLocalSocketRunConfig: LocalSocketRunConfig = mLocalSocketManager.getLocalSocketRunConfig()
    @JvmField
    protected var mFD: Int = -1
    @JvmField
    protected val mCreationTime: Long = System.currentTimeMillis()
    @JvmField
    protected val mOutputStream: OutputStream = SocketOutputStream()
    @JvmField
    protected val mInputStream: InputStream = SocketInputStream()

    init {
        setFD(fd)
        mPeerCred.fillPeerCred(mLocalSocketManager.getContext())
    }

    @JvmOverloads
    fun closeClientSocket(logErrorMessage: Boolean = true): Error? {
        return try {
            close()
            null
        } catch (e: IOException) {
            val error = LocalSocketErrno.ERRNO_CLOSE_CLIENT_SOCKET_FAILED_WITH_EXCEPTION
                .getError(e, mLocalSocketRunConfig.getTitle(), e.message)
            if (logErrorMessage) Logger.logErrorExtended(LOG_TAG, error.getErrorLogString())
            error
        }
    }

    override fun close() {
        if (mFD >= 0) {
            Logger.logVerbose(
                LOG_TAG,
                "Client socket close for \"${mLocalSocketRunConfig.getTitle()}\" server: ${mPeerCred.getMinimalString()}"
            )
            val result = LocalSocketManager.closeSocket(
                mLocalSocketRunConfig.getLogTitle() + " (client)", mFD
            )
            if (result == null || result.retval != 0) {
                throw IOException(JniResult.getErrorString(result))
            }
            setFD(-1)
        }
    }

    fun read(data: ByteArray, bytesRead: MutableInt): Error? {
        bytesRead.value = 0
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD
                .getError(mFD, mLocalSocketRunConfig.getTitle())
        }
        val result = LocalSocketManager.read(
            mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD,
            data,
            if (mLocalSocketRunConfig.getDeadline() > 0) {
                mCreationTime + mLocalSocketRunConfig.getDeadline()
            } else 0
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_CLIENT_SOCKET_FAILED
                .getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result))
        }
        bytesRead.value = result.intData
        return null
    }

    fun send(data: ByteArray): Error? {
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD
                .getError(mFD, mLocalSocketRunConfig.getTitle())
        }
        val result = LocalSocketManager.send(
            mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD,
            data,
            if (mLocalSocketRunConfig.getDeadline() > 0) {
                mCreationTime + mLocalSocketRunConfig.getDeadline()
            } else 0
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_CLIENT_SOCKET_FAILED
                .getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result))
        }
        return null
    }

    fun readDataOnInputStream(data: StringBuilder, closeStreamOnFinish: Boolean): Error? {
        val reader = getInputStreamReader()
        return try {
            var c = reader.read()
            while (c > 0) {
                data.append(c.toChar())
                c = reader.read()
            }
            null
        } catch (e: IOException) {
            LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION
                .getError(
                    mLocalSocketRunConfig.getTitle(),
                    DataUtils.getSpaceIndentedString(e.message, 1)
                )
        } catch (e: Exception) {
            LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION
                .getError(e, mLocalSocketRunConfig.getTitle(), e.message)
        } finally {
            if (closeStreamOnFinish) try { reader.close() } catch (_: IOException) {}
        }
    }

    fun sendDataToOutputStream(data: String, closeStreamOnFinish: Boolean): Error? {
        val writer = getOutputStreamWriter()
        return try {
            BufferedWriter(writer).use {
                it.write(data)
                it.flush()
            }
            null
        } catch (e: IOException) {
            LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION
                .getError(
                    mLocalSocketRunConfig.getTitle(),
                    DataUtils.getSpaceIndentedString(e.message, 1)
                )
        } catch (e: Exception) {
            LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION
                .getError(e, mLocalSocketRunConfig.getTitle(), e.message)
        } finally {
            if (closeStreamOnFinish) try { writer.close() } catch (_: IOException) {}
        }
    }

    fun available(available: MutableInt): Error? = available(available, true)

    fun available(available: MutableInt, checkDeadline: Boolean): Error? {
        available.value = 0
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD
                .getError(mFD, mLocalSocketRunConfig.getTitle())
        }
        if (checkDeadline && mLocalSocketRunConfig.getDeadline() > 0 &&
            System.currentTimeMillis() > mCreationTime + mLocalSocketRunConfig.getDeadline()
        ) return null
        val result = LocalSocketManager.available(
            mLocalSocketRunConfig.getLogTitle() + " (client)", mLocalSocketRunConfig.getFD()
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_CHECK_AVAILABLE_DATA_ON_CLIENT_SOCKET_FAILED
                .getError(mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result))
        }
        available.value = result.intData
        return null
    }

    fun setReadTimeout(): Error? {
        if (mFD >= 0) {
            val result = LocalSocketManager.setSocketReadTimeout(
                mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD,
                mLocalSocketRunConfig.getReceiveTimeout()
            )
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_READ_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(),
                    mLocalSocketRunConfig.getReceiveTimeout(),
                    JniResult.getErrorString(result)
                )
            }
        }
        return null
    }

    fun setWriteTimeout(): Error? {
        if (mFD >= 0) {
            val result = LocalSocketManager.setSocketSendTimeout(
                mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD,
                mLocalSocketRunConfig.getSendTimeout()
            )
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_SEND_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(),
                    mLocalSocketRunConfig.getSendTimeout(),
                    JniResult.getErrorString(result)
                )
            }
        }
        return null
    }

    fun getFD(): Int = mFD
    private fun setFD(fd: Int) { mFD = if (fd >= 0) fd else -1 }
    fun getPeerCred(): PeerCred = mPeerCred
    fun getCreationTime(): Long = mCreationTime
    fun getOutputStream(): OutputStream = mOutputStream
    fun getOutputStreamWriter(): OutputStreamWriter = OutputStreamWriter(mOutputStream)
    fun getInputStream(): InputStream = mInputStream
    fun getInputStreamReader(): InputStreamReader = InputStreamReader(mInputStream)

    fun getLogString(): String = buildString {
        append("Client Socket:")
        append("\n").append(Logger.getSingleLineLogStringEntry("FD", mFD, "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("Creation Time", mCreationTime, "-"))
        append("\n\n\n").append(mPeerCred.getLogString())
    }

    fun getMarkdownString(): String = buildString {
        append("## Client Socket")
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("FD", mFD, "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Creation Time", mCreationTime, "-"))
        append("\n\n\n").append(mPeerCred.getMarkdownString())
    }

    class MutableInt(@JvmField var value: Int)

    protected inner class SocketInputStream : InputStream() {
        private val bytes = ByteArray(1)
        override fun read(): Int {
            val bytesRead = MutableInt(0)
            val error = this@LocalClientSocket.read(bytes, bytesRead)
            if (error != null) throw IOException(error.getErrorMarkdownString())
            return if (bytesRead.value == 0) -1 else bytes[0].toInt()
        }
        override fun read(bytes: ByteArray): Int {
            if (bytes == null) throw NullPointerException("Read buffer can't be null")
            val bytesRead = MutableInt(0)
            val error = this@LocalClientSocket.read(bytes, bytesRead)
            if (error != null) throw IOException(error.getErrorMarkdownString())
            return if (bytesRead.value == 0) -1 else bytesRead.value
        }
        override fun available(): Int {
            val available = MutableInt(0)
            val error = this@LocalClientSocket.available(available)
            if (error != null) throw IOException(error.getErrorMarkdownString())
            return available.value
        }
    }

    protected inner class SocketOutputStream : OutputStream() {
        private val bytes = ByteArray(1)
        override fun write(b: Int) {
            bytes[0] = b.toByte()
            val error = this@LocalClientSocket.send(bytes)
            if (error != null) throw IOException(error.getErrorMarkdownString())
        }
        override fun write(bytes: ByteArray) {
            val error = this@LocalClientSocket.send(bytes)
            if (error != null) throw IOException(error.getErrorMarkdownString())
        }
    }

    companion object {
        const val LOG_TAG = "LocalClientSocket"

        @JvmStatic
        fun closeClientSocket(localSocketManager: LocalSocketManager, fd: Int) {
            LocalClientSocket(localSocketManager, fd, PeerCred()).closeClientSocket(true)
        }
    }
}
