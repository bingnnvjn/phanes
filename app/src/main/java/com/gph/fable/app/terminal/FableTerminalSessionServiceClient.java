package com.gph.fable.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.gph.fable.app.FableService;
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession;
import com.gph.fable.shared.termux.terminal.FableTerminalSessionClientBase;
import com.gph.fable.core.TerminalSession;
import com.gph.fable.core.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class FableTerminalSessionServiceClient extends FableTerminalSessionClientBase {

    private static final String LOG_TAG = "FableTerminalSessionServiceClient";

    private final FableService mService;

    public FableTerminalSessionServiceClient(FableService service) {
        this.mService = service;
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        FableShellSession fableShellSession = mService.getFableShellSessionForTerminalSession(terminalSession);
        if (fableShellSession != null)
            fableShellSession.getExecutionCommand().mPid = pid;
    }

}
