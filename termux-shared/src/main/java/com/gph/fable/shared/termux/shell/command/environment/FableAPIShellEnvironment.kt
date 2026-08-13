package com.gph.fable.shared.termux.shell.command.environment

import android.content.Context
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants

object FableAPIShellEnvironment {
    const val TERMUX_API_APP_ENV_PREFIX = "${TermuxConstants.TERMUX_ENV_PREFIX_ROOT}_API_APP__"
    const val ENV_TERMUX_API_APP__VERSION_NAME = "${TERMUX_API_APP_ENV_PREFIX}VERSION_NAME"

    @JvmStatic
    fun getEnvironment(currentPackageContext: Context): HashMap<String, String>? {
        if (FableUtils.isFableAPIAppInstalled(currentPackageContext) != null) return null
        val packageInfo = PackageUtils.getPackageInfoForPackage(currentPackageContext, TermuxConstants.TERMUX_API_PACKAGE_NAME) ?: return null
        return hashMapOf<String, String>().also {
            ShellEnvironmentUtils.putToEnvIfSet(it, ENV_TERMUX_API_APP__VERSION_NAME, PackageUtils.getVersionNameForPackage(packageInfo))
        }
    }
}
