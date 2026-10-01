package com.gph.fable.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.ListView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.app.FableService
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.TerminalSessionClient
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.shared.interact.ShareUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.interact.TextInputDialogUtils
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession
import com.gph.fable.shared.termux.terminal.FableTerminalSessionClientBase
import com.gph.fable.shared.termux.terminal.io.BellHandler
import java.io.File

/** The [TerminalSessionClient] implementation that may require an [FableActivity] for UI methods. */
class FableTerminalSessionActivityClient(
    @JvmField val mActivity: FableActivity
) : FableTerminalSessionClientBase() {

    private var mBellSoundPool: SoundPool? = null
    private var mBellSoundId: Int = 0

    private val mUiStateHandler = Handler(Looper.getMainLooper())
    private val mUiStatePoller: Runnable = object : Runnable {
        override fun run() {
            pollUiState()
            mUiStateHandler.postDelayed(this, UI_STATE_POLL_INTERVAL_MS)
        }
    }

    private var mUiStatePolling = false
    private var mLastCursorVisible = true

    companion object {
        private const val MAX_SESSIONS = 8
        private const val UI_STATE_POLL_INTERVAL_MS = 200L
        private const val LOG_TAG = "FableTerminalSessionActivityClient"
    }

    /** Should be called when mActivity.onCreate() is called. */
    fun onCreate() {
        checkForFontAndColors()
    }

    /** Should be called when mActivity.onStart() is called. */
    fun onStart() {
        if (mActivity.getFableService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast())
            fableShellSessionListNotifyUpdated()
        }
        mActivity.getTerminalView().onScreenUpdated()
    }

    /** Should be called when mActivity.onResume() is called. */
    fun onResume() {
        loadBellSoundPool()
        startUiStatePolling()
    }

    /** Should be called when mActivity.onStop() is called. */
    fun onStop() {
        setCurrentStoredSession()
        releaseBellSoundPool()
        stopUiStatePolling()
    }

    /** Should be called when mActivity.reloadActivityStyling() is called. */
    fun onReloadActivityStyling() {
        checkForFontAndColors()
    }

    override fun onTextChanged(@NonNull changedSession: TerminalSession) {
        if (!mActivity.isVisible()) return
        if (mActivity.getCurrentSession() === changedSession) {
            mActivity.getTerminalView().onScreenUpdated()
        }
    }

    override fun onTitleChanged(@NonNull updatedSession: TerminalSession) {
        handleTitleChanged(updatedSession)
    }

    /** 标题变更统一入口：旧路径回调与新路径 UI 轮询共用。 */
    private fun handleTitleChanged(updatedSession: TerminalSession) {
        if (!mActivity.isVisible()) return

        if (updatedSession !== mActivity.getCurrentSession()) {
            mActivity.showToast(toToastTitle(updatedSession), true)
        }
        fableShellSessionListNotifyUpdated()
    }

    /** 工单 30：轮询各会话核心缝的 title/bell 消费标记并投递既有行为。 */
    private fun pollUiState() {
        val service = mActivity.getFableService()
        if (service == null || !mActivity.isVisible()) return

        val currentSession = mActivity.getCurrentSession()
        for (fableShellSession in service.getFableShellSessions()) {
            val session = fableShellSession.getTerminalSession() ?: continue
            session.pollUiEvents()
        }

        if (currentSession != null && currentSession.getCoreAdapter() != null) {
            val cursorVisible = currentSession.isCursorEnabled()
            if (cursorVisible != mLastCursorVisible) {
                mLastCursorVisible = cursorVisible
                if (cursorVisible || mActivity.isVisible()) {
                    mActivity.getTerminalView().setTerminalCursorBlinkerState(cursorVisible, false)
                }
            }
        }
    }

    private fun startUiStatePolling() {
        if (mUiStatePolling) return
        mUiStatePolling = true
        mLastCursorVisible = true
        mUiStateHandler.post(mUiStatePoller)
    }

    private fun stopUiStatePolling() {
        mUiStatePolling = false
        mUiStateHandler.removeCallbacks(mUiStatePoller)
    }

    override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
        val service = mActivity.getFableService()
        if (service == null || service.wantsToStop()) {
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index = service.getIndexOfSession(finishedSession)
        var isPluginExecutionCommandWithPendingResult = false
        val fableShellSession = service.getFableShellSession(index)
        if (fableShellSession != null) {
            isPluginExecutionCommandWithPendingResult =
                fableShellSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult()
            if (isPluginExecutionCommandWithPendingResult) {
                Logger.logVerbose(
                    LOG_TAG,
                    "The \"" + finishedSession.mSessionName +
                        "\" session will be force finished automatically since result in pending."
                )
            }
        }

        if (mActivity.isVisible() && finishedSession !== mActivity.getCurrentSession()) {
            if (index >= 0) {
                mActivity.showToast(
                    toToastTitle(finishedSession) + " - " +
                        mActivity.getString(R.string.msg_session_exited),
                    true
                )
            }
        }

        if (mActivity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            if (service.getFableShellSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        } else if (
            finishedSession.getExitStatus() == 0 ||
            finishedSession.getExitStatus() == 130 ||
            isPluginExecutionCommandWithPendingResult
        ) {
            removeFinishedSession(finishedSession)
        }
    }

    override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String?) {
        if (!mActivity.isVisible()) return
        ShareUtils.copyTextToClipboard(mActivity, text)
    }

    override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) {
        if (!mActivity.isVisible()) return

        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null && session != null) {
            session.paste(text)
        }
    }

    override fun onBell(@NonNull session: TerminalSession) {
        handleBell(session)
    }

    /** bell 统一入口：旧路径回调与新路径 UI 轮询共用。 */
    private fun handleBell(@Suppress("UNUSED_PARAMETER") session: TerminalSession) {
        if (!mActivity.isVisible()) return

        when (mActivity.getProperties().getBellBehaviour()) {
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE ->
                BellHandler.getInstance(mActivity).doBell()
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP -> {
                loadBellSoundPool()
                mBellSoundPool?.play(mBellSoundId, 1f, 1f, 1, 0, 1f)
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE -> Unit
        }
    }

    override fun onColorsChanged(@NonNull changedSession: TerminalSession) {
        if (mActivity.getCurrentSession() === changedSession) {
            updateBackgroundColor()
        }
    }

    override fun onTerminalCursorStateChange(enabled: Boolean) {
        val currentSession = mActivity.getCurrentSession()
        if (currentSession != null && currentSession.getCoreAdapter() != null) return

        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(
                LOG_TAG,
                "Ignoring call to start cursor blinking since activity is not visible"
            )
            return
        }
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false)
    }

    override fun setTerminalShellPid(@NonNull terminalSession: TerminalSession, pid: Int) {
        val service = mActivity.getFableService() ?: return
        val fableShellSession = service.getFableShellSessionForTerminalSession(terminalSession)
        if (fableShellSession != null) {
            fableShellSession.getExecutionCommand().mPid = pid
        }
    }

    /** Should be called when mActivity.onResetTerminalSession() is called. */
    fun onResetTerminalSession() {
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true)
    }

    override fun getTerminalCursorStyle(): Int? = mActivity.getProperties().getTerminalCursorStyle()

    @Synchronized
    private fun loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                )
                .build()

            try {
                mBellSoundId = mBellSoundPool?.load(mActivity, com.gph.fable.shared.R.raw.bell, 1) ?: 0
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e)
            }
        }
    }

    @Synchronized
    private fun releaseBellSoundPool() {
        mBellSoundPool?.release()
        mBellSoundPool = null
    }

    /** Try switching to session. */
    fun setCurrentSession(session: TerminalSession?) {
        if (session == null) return

        session.getCwd()?.let { mActivity.recordRecentSession(it) }

        if (mActivity.getFableTerminalView()?.attachSession(session) == true) {
            notifyOfSessionChange()
        }
        checkAndScrollToSession(session)
        updateBackgroundColor()
    }

    fun notifyOfSessionChange() {
        if (!mActivity.isVisible()) return

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            val session = mActivity.getCurrentSession()
            mActivity.showToast(toToastTitle(session), false)
        }
    }

    fun switchToSession(forward: Boolean) {
        val service = mActivity.getFableService() ?: return
        var index = service.getIndexOfSession(mActivity.getCurrentSession())
        val size = service.getFableShellSessionsSize()
        if (forward) {
            index++
            if (index >= size) index = 0
        } else {
            index--
            if (index < 0) index = size - 1
        }

        service.getFableShellSession(index)?.getTerminalSession()?.let { setCurrentSession(it) }
    }

    fun switchToSession(index: Int) {
        val service = mActivity.getFableService() ?: return
        service.getFableShellSession(index)?.getTerminalSession()?.let { setCurrentSession(it) }
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: TerminalSession?) {
        if (sessionToRename == null) return

        TextInputDialogUtils.textInput(
            mActivity,
            R.string.title_rename_session,
            sessionToRename.mSessionName,
            R.string.action_rename_session_confirm,
            { text ->
                renameSession(sessionToRename, text)
                fableShellSessionListNotifyUpdated()
            },
            -1,
            null,
            -1,
            null,
            null
        )
    }

    private fun renameSession(sessionToRename: TerminalSession?, text: String?) {
        if (sessionToRename == null) return
        sessionToRename.mSessionName = text
        val service = mActivity.getFableService()
        if (service != null) {
            service.getFableShellSessionForTerminalSession(sessionToRename)
                ?.getExecutionCommand()?.shellName = text
        }
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mActivity.getFableService() ?: return

        if (service.getFableShellSessionsSize() >= MAX_SESSIONS) {
            AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val currentSession = mActivity.getCurrentSession()
        val workingDirectory = if (currentSession == null) {
            mActivity.getProperties().getDefaultWorkingDirectory()
        } else {
            currentSession.getCwd()
        }
        val newFableShellSession = service.createFableShellSession(
            null, null, null, workingDirectory, isFailSafe, sessionName
        ) ?: return

        val newTerminalSession = newFableShellSession.getTerminalSession() ?: return
        mActivity.recordRecentSession(workingDirectory)
        setCurrentSession(newTerminalSession)
        mActivity.getDrawer().closeDrawers()
    }

    fun setCurrentStoredSession() {
        val currentSession = mActivity.getCurrentSession()
        mActivity.getPreferences().setCurrentSession(currentSession?.mHandle)
    }

    /** The current session as stored or the last one if that does not exist. */
    fun getCurrentStoredSessionOrLast(): TerminalSession? {
        val stored = getCurrentStoredSession()
        if (stored != null) return stored

        val service = mActivity.getFableService() ?: return null
        return service.getLastFableShellSession()?.getTerminalSession()
    }

    private fun getCurrentStoredSession(): TerminalSession? {
        val sessionHandle = mActivity.getPreferences().getCurrentSession() ?: return null
        val service = mActivity.getFableService() ?: return null
        return service.getTerminalSessionForHandle(sessionHandle)
    }

    fun removeFinishedSession(finishedSession: TerminalSession?) {
        val service = mActivity.getFableService() ?: return

        val index = service.removeFableShellSession(finishedSession)
        mActivity.getFableTerminalView()?.onSessionRemoved(finishedSession)

        val size = service.getFableShellSessionsSize()
        if (size == 0) {
            mActivity.finishActivityIfNotFinishing()
            return
        }

        var selectedIndex = index
        if (selectedIndex >= size) selectedIndex = size - 1
        service.getFableShellSession(selectedIndex)?.getTerminalSession()?.let { setCurrentSession(it) }
    }

    fun fableShellSessionListNotifyUpdated() {
        mActivity.fableShellSessionListNotifyUpdated()
    }

    fun checkAndScrollToSession(session: TerminalSession?) {
        if (!mActivity.isVisible()) return
        val service = mActivity.getFableService() ?: return
        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return

        val sessionsListView: ListView? = mActivity.findViewById(R.id.terminal_sessions_list)
        if (sessionsListView == null) return
        sessionsListView.setItemChecked(indexOfSession, true)
        sessionsListView.postDelayed(
            { sessionsListView.smoothScrollToPosition(indexOfSession) },
            1000
        )
    }

    fun toToastTitle(session: TerminalSession?): String? {
        val service = mActivity.getFableService() ?: return null
        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0 || session == null) return null

        val toastTitle = StringBuilder("[" + (indexOfSession + 1) + "]")
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName)
        }
        val title = session.getTitle()
        if (!TextUtils.isEmpty(title)) {
            toastTitle.append(if (session.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    fun checkForFontAndColors() {
        try {
            val fontFile: File = TermuxConstants.TERMUX_FONT_FILE
            updateBackgroundColor()

            val service = mActivity.getFableService()
            if (service != null) {
                for (fableShellSession in service.getFableShellSessions()) {
                    val terminalSession = fableShellSession.getTerminalSession()
                    if (terminalSession != null) {
                        FableTerminalPalette.apply(mActivity, terminalSession.getCoreAdapter())
                    }
                }
            }

            val newTypeface =
                if (fontFile.exists() && fontFile.length() > 0) {
                    Typeface.createFromFile(fontFile)
                } else {
                    Typeface.MONOSPACE
                }
            mActivity.getTerminalView().setTypeface(newTypeface)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    fun updateBackgroundColor() {
        if (!mActivity.isVisible()) return
        val session = mActivity.getCurrentSession() ?: return
        val adapter: CoreAdapter? = session.getCoreAdapter()
        if (adapter != null && adapter.supportsPalette()) {
            val backgroundColor = FableTerminalPalette.resolve(mActivity).background
            mActivity.getWindow().decorView.setBackgroundColor(backgroundColor)
            mActivity.applyStatusBarStyle(backgroundColor)
            return
        }

        mActivity.getWindow().decorView.setBackgroundColor(0xFF000000.toInt())
        mActivity.applyStatusBarStyle(0xFF000000.toInt())
    }
}
