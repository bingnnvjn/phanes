package com.gph.fable.app.terminal

import androidx.annotation.NonNull
import com.gph.fable.app.FableService
import com.gph.fable.core.TerminalSession
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession
import com.gph.fable.shared.termux.terminal.FableTerminalSessionClientBase

/** The [TerminalSessionClient] implementation that may require an [FableService]. */
class FableTerminalSessionServiceClient(
    service: FableService
) : FableTerminalSessionClientBase() {

    private val mService = service

    override fun setTerminalShellPid(@NonNull terminalSession: TerminalSession, pid: Int) {
        val fableShellSession: FableShellSession? =
            mService.getFableShellSessionForTerminalSession(terminalSession)
        if (fableShellSession != null) {
            fableShellSession.getExecutionCommand().mPid = pid
        }
    }
}
