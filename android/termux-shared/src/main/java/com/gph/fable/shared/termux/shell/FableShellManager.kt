package com.gph.fable.shared.termux.shell

import android.content.Context
import android.content.Intent
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.runner.app.AppShell
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession

class FableShellManager(context: Context) {
    @JvmField val mContext: Context = context.applicationContext
    @JvmField val mFableShellSessions = ArrayList<FableShellSession>()
    @JvmField val mFableTasks = ArrayList<AppShell>()
    @JvmField val mPendingPluginExecutionCommands = ArrayList<ExecutionCommand>()

    companion object {
        private var shellManager: FableShellManager? = null
        private var SHELL_ID = 0
        @JvmField var APP_SHELL_NUMBER_SINCE_APP_START = 0
        @JvmField var TERMINAL_SESSION_NUMBER_SINCE_APP_START = 0

        @JvmStatic
        fun init(context: Context): FableShellManager =
            shellManager ?: FableShellManager(context).also { shellManager = it }

        @JvmStatic fun getShellManager(): FableShellManager? = shellManager

        @JvmStatic
        @Synchronized
        fun onActionBootCompleted(context: Context, intent: Intent) {
            val preferences = FableAppSharedPreferences.build(context) ?: return
            preferences.resetAppShellNumberSinceBoot()
            preferences.resetTerminalSessionNumberSinceBoot()
        }

        @JvmStatic
        fun onAppExit(context: Context) {
            APP_SHELL_NUMBER_SINCE_APP_START = 0
            TERMINAL_SESSION_NUMBER_SINCE_APP_START = 0
        }

        @JvmStatic
        @Synchronized fun getNextShellId(): Int = SHELL_ID++

        @JvmStatic
        @Synchronized
        fun getAndIncrementAppShellNumberSinceAppStart(): Int {
            var current = APP_SHELL_NUMBER_SINCE_APP_START
            if (current < 0) current = Int.MAX_VALUE
            APP_SHELL_NUMBER_SINCE_APP_START = current + 1
            if (APP_SHELL_NUMBER_SINCE_APP_START < 0) APP_SHELL_NUMBER_SINCE_APP_START = Int.MAX_VALUE
            return current
        }

        @JvmStatic
        @Synchronized
        fun getAndIncrementTerminalSessionNumberSinceAppStart(): Int {
            var current = TERMINAL_SESSION_NUMBER_SINCE_APP_START
            if (current < 0) current = Int.MAX_VALUE
            TERMINAL_SESSION_NUMBER_SINCE_APP_START = current + 1
            if (TERMINAL_SESSION_NUMBER_SINCE_APP_START < 0) TERMINAL_SESSION_NUMBER_SINCE_APP_START = Int.MAX_VALUE
            return current
        }
    }
}
