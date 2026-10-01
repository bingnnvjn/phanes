package com.gph.fable.shared.net.socket.local

import android.content.Context
import androidx.annotation.Keep
import com.gph.fable.shared.android.ProcessUtils
import com.gph.fable.shared.android.UserUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils

@Keep
class PeerCred internal constructor() {
    companion object {
        const val LOG_TAG = "PeerCred"
        @JvmStatic fun getPeerCredLogString(peerCred: PeerCred?) = peerCred?.getLogString() ?: "null"
        @JvmStatic fun getPeerCredMarkdownString(peerCred: PeerCred?) = peerCred?.getMarkdownString() ?: "null"
    }
    @JvmField var pid = -1
    @JvmField var pname: String? = null
    @JvmField var uid = -1
    @JvmField var uname: String? = null
    @JvmField var gid = -1
    @JvmField var gname: String? = null
    @JvmField var cmdline: String? = null

    fun fillPeerCred(context: Context) { fillUnameAndGname(context); fillPname(context) }
    fun fillUnameAndGname(context: Context) {
        uname = UserUtils.getNameForUid(context, uid)
        gname = if (gid != uid) UserUtils.getNameForUid(context, gid) else uname
    }
    fun fillPname(context: Context) {
        if (pid > 0 && pname == null) pname = ProcessUtils.getAppProcessNameForPid(context, pid)
    }
    fun getProcessString() = if (!pname.isNullOrEmpty()) "$pid ($pname)" else pid.toString()
    fun getUserString() = if (uname != null) "$uid ($uname)" else uid.toString()
    fun getGroupString() = if (gname != null) "$gid ($gname)" else gid.toString()
    fun getMinimalString() = "process=${getProcessString()}, user=${getUserString()}, group=${getGroupString()}"
    fun getLogString(): String = buildString {
        append("Peer Cred:")
        append("\n").append(Logger.getSingleLineLogStringEntry("Process", getProcessString(), "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("User", getUserString(), "-"))
        append("\n").append(Logger.getSingleLineLogStringEntry("Group", getGroupString(), "-"))
        if (cmdline != null) append("\n").append(Logger.getMultiLineLogStringEntry("Cmdline", cmdline, "-"))
    }
    fun getMarkdownString(): String = buildString {
        append("## Peer Cred")
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Process", getProcessString(), "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("User", getUserString(), "-"))
        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Group", getGroupString(), "-"))
        if (cmdline != null) append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Cmdline", cmdline, "-"))
    }
}
