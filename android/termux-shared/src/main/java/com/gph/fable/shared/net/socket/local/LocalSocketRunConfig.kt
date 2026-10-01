package com.gph.fable.shared.net.socket.local

import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import java.io.Serializable
import java.nio.charset.StandardCharsets

open class LocalSocketRunConfig(
    title: String,
    path: String,
    @JvmField val localSocketManagerClient: ILocalSocketManager
) : Serializable {
    @JvmField val title: String = title
    @JvmField val path: String
    @JvmField val abstractNamespaceSocket: Boolean
    @JvmField var fd: Int = -1
    private var receiveTimeout: Int? = null
    private var sendTimeout: Int? = null
    private var deadline: Long? = null
    private var backlog: Int? = null

    init {
        abstractNamespaceSocket = path.toByteArray(StandardCharsets.UTF_8).firstOrNull()?.toInt() == 0
        this.path = if (abstractNamespaceSocket) path else FileUtils.getCanonicalPath(path, null)
    }

    fun getTitle() = title
    fun getLogTitle() = Logger.getDefaultLogTag() + "." + title
    fun getPath() = path
    fun isAbstractNamespaceSocket() = abstractNamespaceSocket
    fun getLocalSocketManagerClient() = localSocketManagerClient
    fun getFD() = fd
    fun setFD(value: Int) { fd = if (value >= 0) value else -1 }
    fun getReceiveTimeout() = receiveTimeout ?: DEFAULT_RECEIVE_TIMEOUT
    fun setReceiveTimeout(value: Int?) { receiveTimeout = value }
    fun getSendTimeout() = sendTimeout ?: DEFAULT_SEND_TIMEOUT
    fun setSendTimeout(value: Int?) { sendTimeout = value }
    fun getDeadline() = deadline ?: DEFAULT_DEADLINE.toLong()
    fun setDeadline(value: Long?) { deadline = value }
    fun getBacklog() = backlog ?: DEFAULT_BACKLOG
    fun setBacklog(value: Int?) { if (value != null && value > 0) backlog = value }

    open fun getLogString() = buildString {
        append(title).append(" Socket Server Run Config:")
        append("\n").append(Logger.getSingleLineLogStringEntry("Path", path, "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("AbstractNamespaceSocket", abstractNamespaceSocket, "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("LocalSocketManagerClient", localSocketManagerClient.javaClass.name, "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("FD", fd, "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("ReceiveTimeout", getReceiveTimeout(), "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("SendTimeout", getSendTimeout(), "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("Deadline", getDeadline(), "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("Backlog", getBacklog(), "-"))
    }

    open fun getMarkdownString() = buildString {
        append("## ").append(title).append(" Socket Server Run Config")
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Path", path, "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("AbstractNamespaceSocket", abstractNamespaceSocket, "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("LocalSocketManagerClient", localSocketManagerClient.javaClass.name, "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("FD", fd, "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("ReceiveTimeout", getReceiveTimeout(), "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("SendTimeout", getSendTimeout(), "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Deadline", getDeadline(), "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Backlog", getBacklog(), "-"))
    }

    override fun toString() = getLogString()

    companion object {
        const val DEFAULT_RECEIVE_TIMEOUT = 10000
        const val DEFAULT_SEND_TIMEOUT = 10000
        const val DEFAULT_DEADLINE = 0
        const val DEFAULT_BACKLOG = 50
        @JvmStatic fun getRunConfigLogString(config: LocalSocketRunConfig?) = config?.getLogString() ?: "null"
        @JvmStatic fun getRunConfigMarkdownString(config: LocalSocketRunConfig?) = config?.getMarkdownString() ?: "null"
    }
}
