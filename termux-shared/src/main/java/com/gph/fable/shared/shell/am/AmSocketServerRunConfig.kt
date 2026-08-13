package com.gph.fable.shared.shell.am

import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.net.socket.local.ILocalSocketManager
import com.gph.fable.shared.net.socket.local.LocalSocketRunConfig

open class AmSocketServerRunConfig(
    title: String,
    path: String,
    localSocketManagerClient: ILocalSocketManager
) : LocalSocketRunConfig(title, path, localSocketManagerClient) {
    private var checkDisplayOverAppsPermission: Boolean? = null

    fun shouldCheckDisplayOverAppsPermission(): Boolean =
        checkDisplayOverAppsPermission ?: DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION

    fun setCheckDisplayOverAppsPermission(value: Boolean?) {
        checkDisplayOverAppsPermission = value
    }

    override fun getLogString(): String =
        super.getLogString() + "\n\n\nAm Command:" +
            "\n" + Logger.getSingleLineLogStringEntry(
            "CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"
        )

    override fun getMarkdownString(): String =
        super.getMarkdownString() + "\n\n\n## Am Command" +
            "\n" + MarkdownUtils.getSingleLineMarkdownStringEntry(
            "CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"
        )

    companion object {
        const val DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION = true
        @JvmStatic
        fun getRunConfigLogString(config: AmSocketServerRunConfig?) = config?.getLogString() ?: "null"
        @JvmStatic
        fun getRunConfigMarkdownString(config: AmSocketServerRunConfig?) = config?.getMarkdownString() ?: "null"
    }
}
