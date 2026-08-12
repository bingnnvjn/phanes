package com.gph.fable.shared.termux.shell.command.runner.terminal;

import android.content.Context;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Joiner;
import com.gph.fable.shared.R;
import com.gph.fable.shared.shell.command.ExecutionCommand;
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils;
import com.gph.fable.shared.shell.command.environment.UnixShellEnvironment;
import com.gph.fable.shared.shell.command.result.ResultData;
import com.gph.fable.shared.errors.Errno;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.shell.command.environment.IShellEnvironment;
import com.gph.fable.shared.shell.ShellUtils;
import com.gph.fable.core.TerminalSession;
import com.gph.fable.core.TerminalSessionClient;
import com.gph.fable.core.session.FableSessionFactory;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A class that maintains info for foreground Fable sessions.
 * It also provides a way to link each {@link TerminalSession} with the {@link ExecutionCommand}
 * that started it.
 */
public class FableShellSession {

    private final TerminalSession mTerminalSession;
    private final ExecutionCommand mExecutionCommand;
    private final FableShellSessionClient mFableShellSessionClient;
    private final boolean mSetStdoutOnExit;

    private static final String LOG_TAG = "FableShellSession";

    private FableShellSession(@NonNull final TerminalSession terminalSession, @NonNull final ExecutionCommand executionCommand,
                          final FableShellSessionClient fableShellSessionClient, final boolean setStdoutOnExit) {
        this.mTerminalSession = terminalSession;
        this.mExecutionCommand = executionCommand;
        this.mFableShellSessionClient = fableShellSessionClient;
        this.mSetStdoutOnExit = setStdoutOnExit;
    }

    /**
     * Start execution of an {@link ExecutionCommand} with {@link Runtime#exec(String[], String[], File)}.
     *
     * The {@link ExecutionCommand#executable}, must be set, {@link ExecutionCommand#commandLabel},
     * {@link ExecutionCommand#arguments} and {@link ExecutionCommand#workingDirectory} may optionally
     * be set.
     *
     * If {@link ExecutionCommand#executable} is {@code null}, then a default shell is automatically
     * chosen.
     *
     * @param currentPackageContext The {@link Context} for operations. This must be the context for
     *                              the current package and not the context of a `sharedUserId` package,
     *                              since environment setup may be dependent on current package.
     * @param executionCommand The {@link ExecutionCommand} containing the information for execution command.
     * @param terminalSessionClient The {@link TerminalSessionClient} interface implementation.
     * @param fableShellSessionClient The {@link FableShellSessionClient} interface implementation.
     * @param shellEnvironmentClient The {@link IShellEnvironment} interface implementation.
     * @param additionalEnvironment The additional shell environment variables to export. Existing
     *                              variables will be overridden.
     * @param setStdoutOnExit If set to {@code true}, then the {@link ResultData#stdout}
     *                        available in the {@link FableShellSessionClient#onFableShellSessionExited(FableShellSession)}
     *                        callback will be set to the {@link TerminalSession} transcript. The session
     *                        transcript will contain both stdout and stderr combined, basically
     *                        anything sent to the the pseudo terminal /dev/pts, including PS1 prefixes.
     *                        Set this to {@code true} only if the session transcript is required,
     *                        since this requires extra processing to get it.
     * @param sessionFactory 会话层抽象缝工厂（工单 26）：唯一实现 =
     *                       RustFableSessionFactory，由调用方（FableService）注入。
     * @return Returns the {@link FableShellSession}. This will be {@code null} if failed to start the execution command.
     */
    public static FableShellSession execute(@NonNull final Context currentPackageContext, @NonNull ExecutionCommand executionCommand,
                                        @NonNull final TerminalSessionClient terminalSessionClient, final FableShellSessionClient fableShellSessionClient,
                                        @NonNull final IShellEnvironment shellEnvironmentClient,
                                        @Nullable HashMap<String, String> additionalEnvironment,
                                        final boolean setStdoutOnExit,
                                        @NonNull final FableSessionFactory sessionFactory) {
        if (executionCommand.executable != null && executionCommand.executable.isEmpty())
            executionCommand.executable = null;
        if (executionCommand.workingDirectory == null || executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath();
        if (executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = "/";

        String defaultBinPath = shellEnvironmentClient.getDefaultBinPath();
        if (defaultBinPath.isEmpty())
            defaultBinPath = "/system/bin";

        boolean isLoginShell = false;
        if (executionCommand.executable == null) {
            if (!executionCommand.isFailsafe) {
                for (String shellBinary : UnixShellEnvironment.LOGIN_SHELL_BINARIES) {
                    File shellFile = new File(defaultBinPath, shellBinary);
                    if (shellFile.canExecute()) {
                        executionCommand.executable = shellFile.getAbsolutePath();
                        break;
                    }
                }
            }

            if (executionCommand.executable == null) {
                // Fall back to system shell as last resort:
                // Do not start a login shell since ~/.profile may cause startup failure if its invalid.
                // /system/bin/sh is provided by mksh (not toybox) and does load .mkshrc but for android its set
                // to /system/etc/mkshrc even though its default is ~/.mkshrc.
                // So /system/etc/mkshrc must still be valid for failsafe session to start properly.
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=663
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=41
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/Android.bp;l=114
                executionCommand.executable = "/system/bin/sh";
            } else {
                isLoginShell = true;
            }

        }

        // Setup command args
        String[] commandArgs = shellEnvironmentClient.setupShellCommandArguments(executionCommand.executable, executionCommand.arguments);

        executionCommand.executable = commandArgs[0];
        String processName = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executionCommand.executable);

        String[] arguments = new String[commandArgs.length];
        arguments[0] = processName;
        if (commandArgs.length > 1) System.arraycopy(commandArgs, 1, arguments, 1, commandArgs.length - 1);

        executionCommand.arguments = arguments;

        if (executionCommand.commandLabel == null)
            executionCommand.commandLabel = processName;

        // Setup command environment
        HashMap<String, String> environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext,
            executionCommand);
        if (additionalEnvironment != null)
            environment.putAll(additionalEnvironment);
        List<String> environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment);
        Collections.sort(environmentList);
        String[] environmentArray = environmentList.toArray(new String[0]);

        if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTING)) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), currentPackageContext.getString(R.string.error_failed_to_execute_fable_shell_session_command, executionCommand.getCommandIdAndLabelLogString()));
            FableShellSession.processFableShellSessionResult(null, executionCommand);
            return null;
        }

        Logger.logDebugExtended(LOG_TAG, executionCommand.toString());
        Logger.logVerboseExtended(LOG_TAG, "\"" + executionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession Environment:\n" +
            Joiner.on("\n").join(environmentArray));

        Logger.logDebug(LOG_TAG, "Running \"" + executionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession");
        TerminalSession terminalSession = new TerminalSession(executionCommand.executable,
            executionCommand.workingDirectory, executionCommand.arguments, environmentArray,
            terminalSessionClient, sessionFactory);

        if (executionCommand.shellName != null) {
            terminalSession.mSessionName = executionCommand.shellName;
        }

        return new FableShellSession(terminalSession, executionCommand, fableShellSessionClient, setStdoutOnExit);
    }

    /**
     * Signal that this {@link FableShellSession} has finished.  This should be called when
     * {@link TerminalSessionClient#onSessionFinished(TerminalSession)} callback is received by the caller.
     *
     * If the processes has finished, then sets {@link ResultData#stdout}, {@link ResultData#stderr}
     * and {@link ResultData#exitCode} for the {@link #mExecutionCommand} of the {@code fableTask}
     * and then calls {@link #processFableShellSessionResult(FableShellSession, ExecutionCommand)} to process the result}.
     *
     */
    public void finish() {
        // If process is still running, then ignore the call
        if (mTerminalSession.isRunning()) return;

        int exitCode = mTerminalSession.getExitStatus();

        if (exitCode == 0)
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession exited normally");
        else
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession exited with code: " + exitCode);

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (mExecutionCommand.isStateFailed()) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession state to ExecutionState.EXECUTED and processing results since it has already failed");
            return;
        }

        mExecutionCommand.resultData.exitCode = exitCode;

        if (this.mSetStdoutOnExit)
            mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false));

        if (!mExecutionCommand.setState(ExecutionCommand.ExecutionState.EXECUTED))
            return;

        FableShellSession.processFableShellSessionResult(this, null);
    }

    /**
     * Kill this {@link FableShellSession} by sending a {@link OsConstants#SIGILL} to its {@link #mTerminalSession}
     * if its still executing.
     *
     * @param context The {@link Context} for operations.
     * @param processResult If set to {@code true}, then the {@link #processFableShellSessionResult(FableShellSession, ExecutionCommand)}
     *                      will be called to process the failure.
     */
    public void killIfExecuting(@NonNull final Context context, boolean processResult) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (mExecutionCommand.hasExecuted()) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession since it has already finished executing");
            return;
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession");
        if (mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                mExecutionCommand.resultData.exitCode = 137; // SIGKILL

                // Get whatever output has been set till now in case its needed
                if (this.mSetStdoutOnExit)
                    mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false));

                FableShellSession.processFableShellSessionResult(this, null);
            }
        }

        // Send SIGKILL to process
        mTerminalSession.finishIfRunning();
    }

    /**
     * Process the results of {@link FableShellSession} or {@link ExecutionCommand}.
     *
     * Only one of {@code fableShellSession} and {@code executionCommand} must be set.
     *
     * If the {@code fableShellSession} and its {@link #mFableShellSessionClient} are not {@code null},
     * then the {@link FableShellSession.FableShellSessionClient#onFableShellSessionExited(FableShellSession)}
     * callback will be called.
     *
     * @param fableShellSession The {@link FableShellSession}, which should be set if
     *                  {@link #execute(Context, ExecutionCommand, TerminalSessionClient, FableShellSessionClient, IShellEnvironment, HashMap, boolean)}
     *                   successfully started the process.
     * @param executionCommand The {@link ExecutionCommand}, which should be set if
     *                          {@link #execute(Context, ExecutionCommand, TerminalSessionClient, FableShellSessionClient, IShellEnvironment, HashMap, boolean)}
     *                          failed to start the process.
     */
    private static void processFableShellSessionResult(final FableShellSession fableShellSession, ExecutionCommand executionCommand) {
        if (fableShellSession != null)
            executionCommand = fableShellSession.mExecutionCommand;

        if (executionCommand == null) return;

        if (executionCommand.shouldNotProcessResults()) {
            Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession result");
            return;
        }

        Logger.logDebug(LOG_TAG, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" FableShellSession result");

        if (fableShellSession != null && fableShellSession.mFableShellSessionClient != null) {
            fableShellSession.mFableShellSessionClient.onFableShellSessionExited(fableShellSession);
        } else {
            // If a callback is not set and execution command didn't fail, then we set success state now
            // Otherwise, the callback host can set it himself when its done with the fableShellSession
            if (!executionCommand.isStateFailed())
                executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS);
        }
    }

    public TerminalSession getTerminalSession() {
        return mTerminalSession;
    }

    public ExecutionCommand getExecutionCommand() {
        return mExecutionCommand;
    }



    public interface FableShellSessionClient {

        /**
         * Callback function for when {@link FableShellSession} exits.
         *
         * @param fableShellSession The {@link FableShellSession} that exited.
         */
        void onFableShellSessionExited(FableShellSession fableShellSession);

    }

}
