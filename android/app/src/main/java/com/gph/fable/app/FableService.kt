package com.gph.fable.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import com.gph.fable.R
import com.gph.fable.app.event.SystemEventReceiver
import com.gph.fable.app.session.RustFableSessionFactory
import com.gph.fable.app.terminal.FableTerminalSessionActivityClient
import com.gph.fable.app.terminal.FableTerminalSessionServiceClient
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.TerminalSessionClient
import com.gph.fable.shared.android.PermissionUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.errors.Errno
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.net.uri.UriUtils
import com.gph.fable.shared.notification.NotificationUtils
import com.gph.fable.shared.shell.ShellUtils
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.ExecutionCommand.Runner
import com.gph.fable.shared.shell.command.ExecutionCommand.ShellCreateMode
import com.gph.fable.shared.shell.command.runner.app.AppShell
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.gph.fable.shared.termux.plugins.FablePluginUtils
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties
import com.gph.fable.shared.termux.shell.FableShellManager
import com.gph.fable.shared.termux.shell.FableShellUtils
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession
import com.gph.fable.shared.termux.terminal.FableTerminalSessionClientBase

/**
 * Foreground service owning terminal sessions and background app-shell tasks.
 *
 * The service is deliberately local-only: [LocalBinder] exposes the service instance to the
 * activity in this process, while terminal sessions use the service client until the activity
 * client is bound.
 */
class FableService : Service(), AppShell.AppShellClient, FableShellSession.FableShellSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    inner class LocalBinder : Binder() {
        @JvmField
        val service: FableService = this@FableService
    }

    private val mBinder: IBinder = LocalBinder()
    private val mHandler = Handler()

    private var mFableTerminalSessionActivityClient: FableTerminalSessionActivityClient? = null
    private val mFableTerminalSessionServiceClient = FableTerminalSessionServiceClient(this)
    private var mProperties: FableAppSharedProperties? = null
    private lateinit var mShellManager: FableShellManager
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    @JvmField
    var mWantsToStop = false

    override fun onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate")

        // FableApplication loads shared properties and FableActivity handles reloads.
        mProperties = FableAppSharedProperties.getProperties()
        mShellManager = checkNotNull(FableShellManager.getShellManager())

        runStartForeground()
        SystemEventReceiver.registerPackageUpdateEvents(this)
    }

    @SuppressLint("Wakelock")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.logDebug(LOG_TAG, "onStartCommand")

        // Run again in case the service is already started and onCreate() is not called.
        runStartForeground()

        val action = if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n${IntentUtils.getIntentString(intent)}")
            intent.action
        } else {
            null
        }

        when (action) {
            TERMUX_SERVICE.ACTION_STOP_SERVICE -> {
                Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received")
                actionStopService()
            }
            TERMUX_SERVICE.ACTION_WAKE_LOCK -> {
                Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received")
                actionAcquireWakeLock()
            }
            TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> {
                Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received")
                actionReleaseWakeLock(true)
            }
            TERMUX_SERVICE.ACTION_SERVICE_EXECUTE -> {
                Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received")
                actionServiceExecute(intent)
            }
            null -> Unit
            else -> Logger.logError(LOG_TAG, "Invalid action: \"$action\"")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy")

        FableShellUtils.clearFableTMPDIR(true)
        actionReleaseWakeLock(false)
        if (!mWantsToStop) killAllFableExecutionCommands()

        FableShellManager.onAppExit(this)
        SystemEventReceiver.unregisterPackageUpdateEvents(this)
        runStopForeground()
    }

    override fun onBind(intent: Intent): IBinder {
        Logger.logVerbose(LOG_TAG, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Logger.logVerbose(LOG_TAG, "onUnbind")
        if (mFableTerminalSessionActivityClient != null) unsetFableTerminalSessionClient()
        return false
    }

    private fun runStartForeground() {
        setupNotificationChannel()
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification()!!)
    }

    private fun runStopForeground() {
        stopForeground(true)
    }

    private fun requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service")
        runStopForeground()
        stopSelf()
    }

    private fun actionStopService() {
        mWantsToStop = true
        killAllFableExecutionCommands()
        requestStopService()
    }

    /** Kill all sessions/tasks and process pending plugin cancellations. */
    @Synchronized
    private fun killAllFableExecutionCommands() {
        Logger.logDebug(
            LOG_TAG,
            "Killing FableShellSessions=${mShellManager.mFableShellSessions.size}, " +
                "FableTasks=${mShellManager.mFableTasks.size}, " +
                "PendingPluginExecutionCommands=${mShellManager.mPendingPluginExecutionCommands.size}"
        )

        val fableShellSessions = ArrayList(mShellManager.mFableShellSessions)
        val fableTasks = ArrayList(mShellManager.mFableTasks)
        val pendingPluginExecutionCommands = ArrayList(mShellManager.mPendingPluginExecutionCommands)

        for (fableShellSession in fableShellSessions) {
            val executionCommand = fableShellSession.getExecutionCommand()
            val processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult()
            fableShellSession.killIfExecuting(this, processResult)
            if (!processResult) mShellManager.mFableShellSessions.remove(fableShellSession)
        }

        for (fableTask in fableTasks) {
            val executionCommand = fableTask.getExecutionCommand()
            if (executionCommand.isPluginExecutionCommandWithPendingResult()) {
                fableTask.killIfExecuting(this, true)
            } else {
                mShellManager.mFableTasks.remove(fableTask)
            }
        }

        for (executionCommand in pendingPluginExecutionCommands) {
            if (!executionCommand.shouldNotProcessResults() &&
                executionCommand.isPluginExecutionCommandWithPendingResult() &&
                executionCommand.setStateFailed(
                    Errno.ERRNO_CANCELLED.code,
                    getString(com.gph.fable.shared.R.string.error_execution_cancelled)
                )
            ) {
                FablePluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
            }
        }
    }

    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        mWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${TermuxConstants.TERMUX_APP_NAME.lowercase()}:service-wakelock"
        )
        mWakeLock?.acquire()

        val wifiManager = getApplicationContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
        mWifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            TermuxConstants.TERMUX_APP_NAME.lowercase()
        )
        mWifiLock?.acquire()

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this)
        }

        updateNotification()
        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully")
    }

    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks")

        mWakeLock?.release()
        mWakeLock = null
        mWifiLock?.release()
        mWifiLock = null

        if (updateNotification) updateNotification()
        Logger.logDebug(LOG_TAG, "WakeLocks released successfully")
    }

    private fun actionServiceExecute(intent: Intent?) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute")
            return
        }

        val executionCommand = ExecutionCommand(FableShellManager.getNextShellId())
        executionCommand.executableUri = intent.data
        executionCommand.isPluginExecutionCommand = true

        executionCommand.runner = IntentUtils.getStringExtraIfSet(
            intent,
            TERMUX_SERVICE.EXTRA_RUNNER,
            if (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)) {
                Runner.APP_SHELL.getName()
            } else {
                Runner.TERMINAL_SESSION.getName()
            }
        )
        if (Runner.runnerOf(executionCommand.runner) == null) {
            val errorMessage = getString(
                R.string.error_fable_service_invalid_execution_command_runner,
                executionCommand.runner
            )
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errorMessage)
            FablePluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            return
        }

        executionCommand.executableUri?.let { executableUri ->
            Logger.logVerbose(
                LOG_TAG,
                "uri: \"$executableUri\", path: \"${executableUri.path}\", fragment: \"${executableUri.fragment}\""
            )
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executableUri)
            executionCommand.arguments =
                IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null)
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                executionCommand.stdin =
                    IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null)
            }
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(
                intent,
                TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL,
                null
            )
        }

        executionCommand.workingDirectory =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null)
        executionCommand.isFailsafe =
            intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION)
        executionCommand.shellName =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null)
        executionCommand.shellCreateMode =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null)
        executionCommand.commandLabel =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command")
        executionCommand.commandDescription =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null)
        executionCommand.commandHelp =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null)
        executionCommand.pluginAPIHelp =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null)
        executionCommand.resultConfig.resultPendingIntent =
            intent.getParcelableExtra<PendingIntent>(TERMUX_SERVICE.EXTRA_PENDING_INTENT)
        executionCommand.resultConfig.resultDirectoryPath =
            IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null)
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile =
                intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false)
            executionCommand.resultConfig.resultFileBasename =
                IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null)
            executionCommand.resultConfig.resultFileOutputFormat =
                IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null)
            executionCommand.resultConfig.resultFileErrorFormat =
                IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null)
            executionCommand.resultConfig.resultFilesSuffix =
                IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null)
        }

        if (executionCommand.shellCreateMode == null) {
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode()
        }

        mShellManager.mPendingPluginExecutionCommands.add(executionCommand)
        when {
            Runner.APP_SHELL.equalsRunner(executionCommand.runner) ->
                executeFableTaskCommand(executionCommand)
            Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner) ->
                executeFableShellSessionCommand(executionCommand)
            else -> {
                val errorMessage = getString(
                    R.string.error_fable_service_unsupported_execution_command_runner,
                    executionCommand.runner
                )
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errorMessage)
                FablePluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            }
        }
    }

    private fun executeFableTaskCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(
            LOG_TAG,
            "Executing background \"${executionCommand.getCommandIdAndLabelLogString()}\" FableTask command"
        )
        if (executionCommand.shellName == null && executionCommand.executable != null) {
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)
        }

        var newFableTask: AppShell? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME == shellCreateMode) {
            newFableTask = getFableTaskForShellName(executionCommand.shellName)
            if (newFableTask != null) {
                Logger.logVerbose(
                    LOG_TAG,
                    "Existing FableTask with \"${executionCommand.shellName}\" shell name found for shell create mode \"${shellCreateMode.getMode()}\""
                )
            } else {
                Logger.logVerbose(
                    LOG_TAG,
                    "No existing FableTask with \"${executionCommand.shellName}\" shell name found for shell create mode \"${shellCreateMode.getMode()}\""
                )
            }
        }

        if (newFableTask == null) createFableTask(executionCommand)
    }

    fun createFableTask(
        executablePath: String?,
        arguments: Array<String>?,
        stdin: String?,
        workingDirectory: String?
    ): AppShell? {
        return createFableTask(
            ExecutionCommand(
                FableShellManager.getNextShellId(),
                executablePath,
                arguments,
                stdin,
                workingDirectory,
                Runner.APP_SHELL.getName(),
                false
            )
        )
    }

    @Synchronized
    fun createFableTask(executionCommand: ExecutionCommand?): AppShell? {
        if (executionCommand == null) return null

        Logger.logDebug(
            LOG_TAG,
            "Creating \"${executionCommand.getCommandIdAndLabelLogString()}\" FableTask"
        )
        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring wrong runner \"${executionCommand.runner}\" command passed to createFableTask()"
            )
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true
        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        val newFableTask = AppShell.execute(
            this,
            executionCommand,
            this,
            FableShellEnvironment(),
            null,
            false
        )
        if (newFableTask == null) {
            Logger.logError(
                LOG_TAG,
                "Failed to execute new FableTask command for:\n${executionCommand.getCommandIdAndLabelLogString()}"
            )
            if (executionCommand.isPluginExecutionCommand) {
                FablePluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mShellManager.mFableTasks.add(newFableTask)
        if (executionCommand.isPluginExecutionCommand) {
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand)
        }
        updateNotification()
        return newFableTask
    }

    override fun onAppShellExited(fableTask: AppShell) {
        mHandler.post {
            val executionCommand = fableTask.getExecutionCommand()
            Logger.logVerbose(
                LOG_TAG,
                "The onFableTaskExited() callback called for \"${executionCommand.getCommandIdAndLabelLogString()}\" FableTask command"
            )
            if (executionCommand.isPluginExecutionCommand) {
                FablePluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
            }
            mShellManager.mFableTasks.remove(fableTask)
            updateNotification()
        }
    }

    private fun executeFableShellSessionCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(
            LOG_TAG,
            "Executing foreground \"${executionCommand.getCommandIdAndLabelLogString()}\" FableShellSession command"
        )
        if (executionCommand.shellName == null && executionCommand.executable != null) {
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)
        }

        var newFableShellSession: FableShellSession? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME == shellCreateMode) {
            newFableShellSession = getFableShellSessionForShellName(executionCommand.shellName)
            if (newFableShellSession != null) {
                Logger.logVerbose(
                    LOG_TAG,
                    "Existing FableShellSession with \"${executionCommand.shellName}\" shell name found for shell create mode \"${shellCreateMode.getMode()}\""
                )
            } else {
                Logger.logVerbose(
                    LOG_TAG,
                    "No existing FableShellSession with \"${executionCommand.shellName}\" shell name found for shell create mode \"${shellCreateMode.getMode()}\""
                )
            }
        }

        if (newFableShellSession == null) {
            newFableShellSession = createFableShellSession(executionCommand)
        }
        val terminalSession = newFableShellSession?.getTerminalSession() ?: return
        handleSessionAction(
            DataUtils.getIntFromString(
                executionCommand.sessionAction,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY
            ),
            terminalSession
        )
    }

    fun createFableShellSession(
        executablePath: String?,
        arguments: Array<String>?,
        stdin: String?,
        workingDirectory: String?,
        isFailSafe: Boolean,
        sessionName: String?
    ): FableShellSession? {
        val executionCommand = ExecutionCommand(
            FableShellManager.getNextShellId(),
            executablePath,
            arguments,
            stdin,
            workingDirectory,
            Runner.TERMINAL_SESSION.getName(),
            isFailSafe
        )
        executionCommand.shellName = sessionName
        return createFableShellSession(executionCommand)
    }

    @Synchronized
    fun createFableShellSession(executionCommand: ExecutionCommand?): FableShellSession? {
        if (executionCommand == null) return null

        Logger.logDebug(
            LOG_TAG,
            "Creating \"${executionCommand.getCommandIdAndLabelLogString()}\" FableShellSession"
        )
        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring wrong runner \"${executionCommand.runner}\" command passed to createFableShellSession()"
            )
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true
        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        // The Rust factory is the sole session implementation.
        val newFableShellSession = FableShellSession.execute(
            this,
            executionCommand,
            getFableTerminalSessionClient(),
            this,
            FableShellEnvironment(),
            null,
            executionCommand.isPluginExecutionCommand,
            RustFableSessionFactory
        )
        if (newFableShellSession == null) {
            Logger.logError(
                LOG_TAG,
                "Failed to execute new FableShellSession command for:\n${executionCommand.getCommandIdAndLabelLogString()}"
            )
            if (executionCommand.isPluginExecutionCommand) {
                FablePluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mShellManager.mFableShellSessions.add(newFableShellSession)
        if (executionCommand.isPluginExecutionCommand) {
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand)
        }
        mFableTerminalSessionActivityClient?.fableShellSessionListNotifyUpdated()
        updateNotification()
        FableActivity.updateFableActivityStyling(this, false)
        return newFableShellSession
    }

    @Synchronized
    fun removeFableShellSession(sessionToRemove: TerminalSession?): Int {
        val index = getIndexOfSession(sessionToRemove)
        if (index >= 0) mShellManager.mFableShellSessions[index].finish()
        return index
    }

    override fun onFableShellSessionExited(fableShellSession: FableShellSession) {
        val executionCommand = fableShellSession.getExecutionCommand()
        Logger.logVerbose(
            LOG_TAG,
            "The onFableShellSessionExited() callback called for \"${executionCommand.getCommandIdAndLabelLogString()}\" FableShellSession command"
        )
        if (executionCommand.isPluginExecutionCommand) {
            FablePluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
        }
        mShellManager.mFableShellSessions.remove(fableShellSession)
        mFableTerminalSessionActivityClient?.fableShellSessionListNotifyUpdated()
        updateNotification()
    }

    private fun processShellCreateMode(executionCommand: ExecutionCommand): ShellCreateMode? {
        return when {
            ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode) -> ShellCreateMode.ALWAYS
            ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode) -> {
                if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                    FablePluginUtils.setAndProcessPluginExecutionCommandError(
                        this,
                        LOG_TAG,
                        executionCommand,
                        false,
                        getString(
                            R.string.error_fable_service_execution_command_shell_name_unset,
                            executionCommand.shellCreateMode
                        )
                    )
                    null
                } else {
                    ShellCreateMode.NO_SHELL_WITH_NAME
                }
            }
            else -> {
                FablePluginUtils.setAndProcessPluginExecutionCommandError(
                    this,
                    LOG_TAG,
                    executionCommand,
                    false,
                    getString(
                        R.string.error_fable_service_unsupported_execution_command_shell_create_mode,
                        executionCommand.shellCreateMode
                    )
                )
                null
            }
        }
    }

    private fun handleSessionAction(sessionAction: Int, newTerminalSession: TerminalSession) {
        Logger.logDebug(
            LOG_TAG,
            "Processing sessionAction \"$sessionAction\" for session \"${newTerminalSession.mSessionName}\""
        )
        when (sessionAction) {
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mFableTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
                startFableActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY -> {
                if (getFableShellSessionsSize() == 1) setCurrentStoredTerminalSession(newTerminalSession)
                startFableActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mFableTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                if (getFableShellSessionsSize() == 1) setCurrentStoredTerminalSession(newTerminalSession)
            }
            else -> {
                Logger.logError(
                    LOG_TAG,
                    "Invalid sessionAction: \"$sessionAction\". Force using default sessionAction."
                )
                handleSessionAction(
                    TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY,
                    newTerminalSession
                )
            }
        }
    }

    private fun startFableActivity() {
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
                FableActivity.startFableActivity(this)
        } else {
            val preferences = FableAppSharedPreferences.build(this) ?: return
            if (preferences.arePluginErrorNotificationsEnabled(false)) {
                Logger.showToast(
                    this,
                    getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal),
                    true
                )
            }
        }
    }

    @Synchronized
    fun getFableTerminalSessionClient(): FableTerminalSessionClientBase {
        return mFableTerminalSessionActivityClient ?: mFableTerminalSessionServiceClient
    }

    @Synchronized
    fun setFableTerminalSessionClient(
        fableTerminalSessionActivityClient: FableTerminalSessionActivityClient?
    ) {
        mFableTerminalSessionActivityClient = fableTerminalSessionActivityClient
        for (fableShellSession in mShellManager.mFableShellSessions) {
            fableShellSession.getTerminalSession()
                .updateTerminalSessionClient(mFableTerminalSessionActivityClient)
        }
    }

    @Synchronized
    fun unsetFableTerminalSessionClient() {
        for (fableShellSession in mShellManager.mFableShellSessions) {
            fableShellSession.getTerminalSession()
                .updateTerminalSessionClient(mFableTerminalSessionServiceClient)
        }
        mFableTerminalSessionActivityClient = null
    }

    private fun buildNotification(): Notification? {
        val res: Resources = resources
        val notificationIntent = FableActivity.newInstance(this)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val sessionCount = getFableShellSessionsSize()
        val taskCount = mShellManager.mFableTasks.size
        var notificationText =
            res.getQuantityString(R.plurals.notification_sessions_count, sessionCount, sessionCount)
        if (taskCount > 0) {
            notificationText += ", " +
                res.getQuantityString(R.plurals.notification_tasks_count, taskCount, taskCount)
        }

        val wakeLockHeld = mWakeLock != null
        if (wakeLockHeld) notificationText += res.getString(R.string.notification_wake_lock_held)
        val priority = if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW

        val builder = NotificationUtils.geNotificationBuilder(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            priority,
            TermuxConstants.TERMUX_APP_NAME,
            notificationText,
            null,
            contentIntent,
            null,
            NotificationUtils.NOTIFICATION_MODE_SILENT
        ) ?: return null

        builder.setShowWhen(false)
        builder.setSmallIcon(R.drawable.ic_service_notification)
        builder.setColor(0xFF607D8B.toInt())
        builder.setOngoing(true)

        val exitIntent = Intent(this, FableService::class.java)
            .setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE)
        builder.addAction(
            android.R.drawable.ic_delete,
            res.getString(R.string.notification_action_exit),
            PendingIntent.getService(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        val newWakeAction =
            if (wakeLockHeld) TERMUX_SERVICE.ACTION_WAKE_UNLOCK else TERMUX_SERVICE.ACTION_WAKE_LOCK
        val toggleWakeLockIntent = Intent(this, FableService::class.java).setAction(newWakeAction)
        val actionTitle = res.getString(
            if (wakeLockHeld) R.string.notification_action_wake_unlock
            else R.string.notification_action_wake_lock
        )
        val actionIcon =
            if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(
            actionIcon,
            actionTitle,
            PendingIntent.getService(this, 0, toggleWakeLockIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        return builder.build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        NotificationUtils.setupNotificationChannel(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            getString(R.string.fable_app_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
    }

    @Synchronized
    private fun updateNotification() {
        if (mWakeLock == null &&
            mShellManager.mFableShellSessions.isEmpty() &&
            mShellManager.mFableTasks.isEmpty()
        ) {
            requestStopService()
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                TermuxConstants.TERMUX_APP_NOTIFICATION_ID,
                buildNotification()!!
            )
        }
    }

    private fun setCurrentStoredTerminalSession(terminalSession: TerminalSession?) {
        if (terminalSession == null) return
        val preferences = FableAppSharedPreferences.build(this) ?: return
        preferences.setCurrentSession(terminalSession.mHandle)
    }

    @Synchronized
    fun isFableShellSessionsEmpty(): Boolean = mShellManager.mFableShellSessions.isEmpty()

    @Synchronized
    fun getFableShellSessionsSize(): Int = mShellManager.mFableShellSessions.size

    @Synchronized
    fun getFableShellSessions(): List<FableShellSession> = mShellManager.mFableShellSessions

    @Synchronized
    fun getFableShellSession(index: Int): FableShellSession? {
        return if (index >= 0 && index < mShellManager.mFableShellSessions.size) {
            mShellManager.mFableShellSessions[index]
        } else {
            null
        }
    }

    @Synchronized
    fun getFableShellSessionForTerminalSession(
        terminalSession: TerminalSession?
    ): FableShellSession? {
        if (terminalSession == null) return null
        return mShellManager.mFableShellSessions.firstOrNull {
            it.getTerminalSession().equals(terminalSession)
        }
    }

    @Synchronized
    fun getLastFableShellSession(): FableShellSession? {
        return mShellManager.mFableShellSessions.lastOrNull()
    }

    @Synchronized
    fun getIndexOfSession(terminalSession: TerminalSession?): Int {
        if (terminalSession == null) return -1
        return mShellManager.mFableShellSessions.indexOfFirst {
            it.getTerminalSession().equals(terminalSession)
        }
    }

    @Synchronized
    fun getTerminalSessionForHandle(sessionHandle: String?): TerminalSession? {
        for (fableShellSession in mShellManager.mFableShellSessions) {
            val terminalSession = fableShellSession.getTerminalSession()
            if (terminalSession.mHandle == sessionHandle) return terminalSession
        }
        return null
    }

    @Synchronized
    fun getFableTaskForShellName(name: String?): AppShell? {
        if (DataUtils.isNullOrEmpty(name)) return null
        for (appShell in mShellManager.mFableTasks) {
            val shellName = appShell.getExecutionCommand().shellName
            if (shellName != null && shellName == name) return appShell
        }
        return null
    }

    @Synchronized
    fun getFableShellSessionForShellName(name: String?): FableShellSession? {
        if (DataUtils.isNullOrEmpty(name)) return null
        for (fableShellSession in mShellManager.mFableShellSessions) {
            val shellName = fableShellSession.getExecutionCommand().shellName
            if (shellName != null && shellName == name) return fableShellSession
        }
        return null
    }

    fun wantsToStop(): Boolean = mWantsToStop

    private companion object {
        const val LOG_TAG = "FableService"
    }
}
