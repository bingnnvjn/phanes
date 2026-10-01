package com.gph.fable.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.os.Environment
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.drawerlayout.widget.DrawerLayout
import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.app.models.UserAction
import com.gph.fable.app.terminal.io.KeyboardShortcut
import com.gph.fable.core.KeyHandler
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.shared.activities.ReportActivity
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.interact.MessageDialogUtils
import com.gph.fable.shared.interact.ShareUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.models.ReportInfo
import com.gph.fable.shared.shell.ShellUtils
import com.gph.fable.shared.termux.FableBootstrap
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.data.FableUrlUtils
import com.gph.fable.shared.termux.extrakeys.SpecialButton
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants
import com.gph.fable.shared.termux.terminal.FableTerminalViewClientBase
import com.gph.fable.shared.view.KeyboardUtils
import com.gph.fable.shared.view.ViewUtils
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

class FableTerminalViewClient(
    @JvmField val mActivity: FableActivity,
    @JvmField val mFableTerminalSessionActivityClient: FableTerminalSessionActivityClient
) : FableTerminalViewClientBase() {

    /** Keeping track of the special keys acting as Ctrl and Fn for soft/hardware keyboards. */
    @JvmField
    var mVirtualControlKeyDown: Boolean = false

    @JvmField
    var mVirtualFnKeyDown: Boolean = false

    private var mShowSoftKeyboardRunnable: Runnable? = null
    private var mShowSoftKeyboardIgnoreOnce = false
    private var mShowSoftKeyboardWithDelayOnce = false
    private var mTerminalCursorBlinkerStateAlreadySet = false
    private var mSessionShortcuts: List<KeyboardShortcut>? = null

    companion object {
        private const val LOG_TAG = "FableTerminalViewClient"
    }

    fun getActivity(): FableActivity = mActivity

    /** Should be called when mActivity.onCreate() is called. */
    fun onCreate() {
        onReloadProperties()
        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize())
        mActivity.getTerminalView().keepScreenOn =
            mActivity.getPreferences().shouldKeepScreenOn()
    }

    /** Should be called when mActivity.onStart() is called. */
    fun onStart() {
        val isTerminalViewKeyLoggingEnabled =
            mActivity.getPreferences().isTerminalViewKeyLoggingEnabled()
        mActivity.getTerminalView()
            .setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled)
        mActivity.getFableActivityRootView()
            .setIsRootViewLoggingEnabled(isTerminalViewKeyLoggingEnabled)
        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled)
        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize())
    }

    /** Should be called when mActivity.onResume() is called. */
    fun onResume() {
        setSoftKeyboardState(true, mActivity.isActivityRecreated())
        mTerminalCursorBlinkerStateAlreadySet = false

        if (mActivity.getTerminalView().getCurrentSession() != null) {
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    /** Should be called when mActivity.onStop() is called. */
    fun onStop() {
        setTerminalCursorBlinkerState(false)
    }

    /** Should be called when mActivity.reloadProperties() is called. */
    fun onReloadProperties() {
        setSessionShortcuts()
    }

    /** Should be called when mActivity.reloadActivityStyling() is called. */
    fun onReloadActivityStyling() {
        setSoftKeyboardState(false, true)
        setTerminalCursorBlinkerState(true)
    }

    /** Called when the first session is attached and sized. */
    override fun onEmulatorSet() {
        if (!mTerminalCursorBlinkerStateAlreadySet) {
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val session = mActivity.getCurrentSession()!!
        val mouseTrackingActive = session.isMouseTrackingActive()

        if (mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            val columnAndRow = mActivity.getTerminalView().getColumnAndRow(e, true)
            val adapter: CoreAdapter? = session.getCoreAdapter()
            val wordAtTap = adapter?.getWordAt(columnAndRow[0], columnAndRow[1]) ?: ""
            val urlSet = FableUrlUtils.extractUrls(wordAtTap)

            if (urlSet.isNotEmpty()) {
                val url = urlSet.iterator().next().toString()
                ShareUtils.openUrl(mActivity, url)
                return
            }
        }

        if (!mouseTrackingActive && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                Logger.logVerbose(
                    LOG_TAG,
                    "Not showing soft keyboard onSingleTapUp since its disabled"
                )
            }
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean =
        mActivity.getProperties().isBackKeyTheEscapeKey()

    override fun shouldEnforceCharBasedInput(): Boolean =
        mActivity.getProperties().isEnforcingCharBasedInput()

    override fun shouldUseCtrlSpaceWorkaround(): Boolean =
        mActivity.getProperties().isUsingCtrlSpaceWorkaround()

    override fun isTerminalViewSelected(): Boolean =
        mActivity.getTerminalToolbarViewPager() == null ||
            mActivity.isTerminalViewSelected() ||
            mActivity.getTerminalView().hasFocus()

    override fun copyModeChanged(copyMode: Boolean) {
        FableDiagnostics.append("selection:copyModeChanged=$copyMode")
        mActivity.getDrawer().setDrawerLockMode(
            if (copyMode) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
    }

    @SuppressLint("RtlHardcoded")
    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent,
        currentSession: TerminalSession
    ): Boolean {
        if (handleVirtualKeys(keyCode, e, true)) return true

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mFableTerminalSessionActivityClient.removeFinishedSession(currentSession)
            return true
        } else if (
            !mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed && e.isAltPressed
        ) {
            val unicodeChar = e.getUnicodeChar(0)
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'.code ->
                    mFableTerminalSessionActivityClient.switchToSession(true)
                keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p'.code ->
                    mFableTerminalSessionActivityClient.switchToSession(false)
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                    mActivity.getDrawer().openDrawer(Gravity.LEFT)
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ->
                    mActivity.getDrawer().closeDrawers()
                unicodeChar == 'k'.code -> onToggleSoftKeyboardRequest()
                unicodeChar == 'm'.code -> mActivity.getTerminalView().showContextMenu()
                unicodeChar == 'r'.code ->
                    mFableTerminalSessionActivityClient.renameSession(currentSession)
                unicodeChar == 'c'.code ->
                    mFableTerminalSessionActivityClient.addNewSession(false, null)
                unicodeChar == 'u'.code -> showUrlSelection()
                unicodeChar == 'v'.code -> doPaste()
                unicodeChar == '+'.code ||
                    e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.code ->
                    changeFontSize(true)
                unicodeChar == '-'.code -> changeFontSize(false)
                unicodeChar in '1'.code..'9'.code ->
                    mFableTerminalSessionActivityClient.switchToSession(unicodeChar - '1'.code)
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            mActivity.getTerminalView().getCurrentSession() == null
        ) {
            mActivity.finishActivityIfNotFinishing()
            return true
        }
        return handleVirtualKeys(keyCode, e, false)
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val inputDevice = event.device
        if (mActivity.getProperties().areVirtualVolumeKeysDisabled()) {
            return false
        } else if (
            inputDevice != null &&
            inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return false
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down
            return true
        }
        return false
    }

    override fun readControlKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown

    override fun readAltKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.ALT)

    override fun readShiftKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.SHIFT)

    override fun readFnKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.FN)

    fun readExtraKeysSpecialButton(specialButton: SpecialButton): Boolean {
        val extraKeysView = mActivity.getExtraKeysView() ?: return false
        val state = extraKeysView.readSpecialButton(specialButton, true)
        if (state == null) {
            Logger.logError(
                LOG_TAG,
                "Failed to read an unregistered $specialButton special button value from extra keys."
            )
            return false
        }
        return state
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession
    ): Boolean {
        if (mVirtualFnKeyDown) {
            var resultingKeyCode = -1
            var resultingCodePoint = -1
            var altDown = false
            val lowerCase = Character.toLowerCase(codePoint)
            when (lowerCase) {
                'w'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP
                'a'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT
                's'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
                'd'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT
                'p'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP
                'n'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN
                't'.code -> resultingKeyCode = KeyEvent.KEYCODE_TAB
                'i'.code -> resultingKeyCode = KeyEvent.KEYCODE_INSERT
                'h'.code -> resultingCodePoint = '~'.code
                'u'.code -> resultingCodePoint = '_'.code
                'l'.code -> resultingCodePoint = '|'.code
                in '1'.code..'9'.code ->
                    resultingKeyCode = (codePoint - '1'.code) + KeyEvent.KEYCODE_F1
                '0'.code -> resultingKeyCode = KeyEvent.KEYCODE_F10
                'e'.code -> resultingCodePoint = 27
                '.'.code -> resultingCodePoint = 28
                'b'.code, 'f'.code, 'x'.code -> {
                    resultingCodePoint = lowerCase
                    altDown = true
                }
                'v'.code -> {
                    resultingCodePoint = -1
                    val audio =
                        mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_SAME,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                'q'.code, 'k'.code -> {
                    mActivity.toggleTerminalToolbar()
                    mVirtualFnKeyDown = false
                }
            }

            if (resultingKeyCode != -1) {
                session.write(
                    KeyHandler.getCode(
                        resultingKeyCode,
                        0,
                        session.isCursorKeysApplicationMode(),
                        session.isKeypadApplicationMode()
                    )
                )
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint)
            }
            return true
        } else if (ctrlDown) {
            if (codePoint == 106 && !session.isRunning()) {
                mFableTerminalSessionActivityClient.removeFinishedSession(session)
                return true
            }

            val shortcuts = mSessionShortcuts
            if (!shortcuts.isNullOrEmpty()) {
                val codePointLowerCase = Character.toLowerCase(codePoint)
                for (i in shortcuts.size - 1 downTo 0) {
                    val shortcut = shortcuts[i]
                    if (codePointLowerCase == shortcut.codePoint) {
                        when (shortcut.shortcutAction) {
                            TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION -> {
                                mFableTerminalSessionActivityClient.addNewSession(false, null)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_NEXT_SESSION -> {
                                mFableTerminalSessionActivityClient.switchToSession(true)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_PREVIOUS_SESSION -> {
                                mFableTerminalSessionActivityClient.switchToSession(false)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION -> {
                                mFableTerminalSessionActivityClient.renameSession(
                                    mActivity.getCurrentSession()
                                )
                                return true
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /** Set the terminal sessions shortcuts. */
    private fun setSessionShortcuts() {
        val shortcuts = ArrayList<KeyboardShortcut>()
        for (entry in TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entries) {
            val codePoint =
                mActivity.getProperties().getInternalPropertyValue(entry.key, true) as? Int
            if (codePoint != null) {
                shortcuts.add(KeyboardShortcut(codePoint, entry.value))
            }
        }
        mSessionShortcuts = shortcuts
    }

    fun changeFontSize(increase: Boolean) {
        mActivity.getPreferences().changeFontSize(increase)
        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize())
    }

    /**
     * Called when user requests the soft keyboard to be toggled via drawer, extra keys,
     * or ctrl+alt+k.
     */
    fun onToggleSoftKeyboardRequest() {
        if (mActivity.getProperties().shouldEnableDisableSoftKeyboardOnToggle()) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle")
                mActivity.getPreferences().setSoftKeyboardEnabled(false)
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle")
                mActivity.getPreferences().setSoftKeyboardEnabled(true)
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                if (mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false
                    mActivity.getTerminalView()
                        .postDelayed(getShowSoftKeyboardRunnable(), 500)
                    mActivity.getTerminalView().requestFocus()
                } else {
                    KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
                }
            }
        } else {
            if (!mActivity.getPreferences().isSoftKeyboardEnabled()) {
                Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle")
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle")
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                KeyboardUtils.toggleSoftKeyboard(mActivity)
            }
        }
    }

    fun setSoftKeyboardState(isStartup: Boolean, isReloadFableProperties: Boolean) {
        var noShowKeyboard = false

        if (
            KeyboardUtils.shouldSoftKeyboardBeDisabled(
                mActivity,
                mActivity.getPreferences().isSoftKeyboardEnabled(),
                mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware()
            )
        ) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard")
            KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            mActivity.getTerminalView().requestFocus()
            noShowKeyboard = true
            if (isStartup && mActivity.isOnResumeAfterOnCreate()) {
                mShowSoftKeyboardWithDelayOnce = true
            }
        } else {
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity)
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)

            if (isStartup && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup")
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity)
                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView())
                mActivity.getTerminalView().requestFocus()
                noShowKeyboard = true
                mShowSoftKeyboardIgnoreOnce = true
            }
        }

        mActivity.getTerminalView().setOnFocusChangeListener { _, hasFocus ->
            var textInputViewHasFocus = false
            val textInputView: EditText? =
                mActivity.findViewById(R.id.terminal_toolbar_text_input)
            if (textInputView != null) {
                textInputViewHasFocus = textInputView.hasFocus()
            }

            if (hasFocus || textInputViewHasFocus) {
                if (mShowSoftKeyboardIgnoreOnce) {
                    mShowSoftKeyboardIgnoreOnce = false
                    return@setOnFocusChangeListener
                }
                Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change")
            } else {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change")
            }

            KeyboardUtils.setSoftKeyboardVisibility(
                getShowSoftKeyboardRunnable(),
                mActivity,
                mActivity.getTerminalView(),
                hasFocus || textInputViewHasFocus
            )
        }

        if (!isReloadFableProperties && !noShowKeyboard) {
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard")
            mActivity.getTerminalView().requestFocus()
            mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), 300)
        }
    }

    private fun getShowSoftKeyboardRunnable(): Runnable {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = Runnable {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
            }
        }
        return mShowSoftKeyboardRunnable!!
    }

    fun setTerminalCursorBlinkerState(start: Boolean) {
        if (start) {
            if (
                mActivity.getTerminalView()
                    .setTerminalCursorBlinkerRate(
                        mActivity.getProperties().getTerminalCursorBlinkRate()
                    )
            ) {
                mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true)
            } else {
                Logger.logError(LOG_TAG, "Failed to start cursor blinker")
            }
        } else {
            mActivity.getTerminalView().setTerminalCursorBlinkerState(false, true)
        }
    }

    fun shareSessionTranscript() {
        val session = mActivity.getCurrentSession() ?: return
        var transcriptText =
            ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return
        transcriptText = DataUtils.getTruncatedCommandOutput(
            transcriptText,
            DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES,
            false,
            true,
            false
        )!!.trim()
        ShareUtils.shareText(
            mActivity,
            mActivity.getString(R.string.title_share_transcript),
            transcriptText,
            mActivity.getString(R.string.title_share_transcript_with)
        )
    }

    fun shareSelectedText() {
        val selectedText = mActivity.getTerminalView().getStoredSelectedText()
        if (DataUtils.isNullOrEmpty(selectedText)) return
        ShareUtils.shareText(
            mActivity,
            mActivity.getString(R.string.title_share_selected_text),
            selectedText,
            mActivity.getString(R.string.title_share_selected_text_with)
        )
    }

    fun showUrlSelection() {
        val session = mActivity.getCurrentSession() ?: return
        val text = ShellUtils.getTerminalSessionTranscriptText(session, true, true)!!
        val urlSet = FableUrlUtils.extractUrls(text)
        if (urlSet.isEmpty()) {
            AlertDialog.Builder(mActivity)
                .setMessage(R.string.title_select_url_none_found)
                .show()
            return
        }

        val urls = urlSet.toTypedArray()
        Collections.reverse(Arrays.asList(*urls))

        val dialog = AlertDialog.Builder(mActivity)
            .setItems(urls) { _, which ->
                val url = urls[which].toString()
                ShareUtils.copyTextToClipboard(
                    mActivity,
                    url,
                    mActivity.getString(R.string.msg_select_url_copied_to_clipboard)
                )
            }
            .setTitle(R.string.title_select_url_dialog)
            .create()

        dialog.setOnShowListener {
            val listView: ListView = dialog.listView
            listView.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                ShareUtils.openUrl(mActivity, urls[position].toString())
                true
            }
        }
        dialog.show()
    }

    fun reportIssueFromTranscript() {
        val session = mActivity.getCurrentSession() ?: return
        val transcriptText =
            ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return

        MessageDialogUtils.showMessage(
            mActivity,
            TermuxConstants.TERMUX_APP_NAME + " Report Issue",
            mActivity.getString(R.string.msg_add_fable_debug_info),
            mActivity.getString(com.gph.fable.shared.R.string.action_yes),
            { _, _ -> reportIssueFromTranscript(transcriptText, true) },
            mActivity.getString(com.gph.fable.shared.R.string.action_no),
            { _, _ -> reportIssueFromTranscript(transcriptText, false) },
            null
        )
    }

    private fun reportIssueFromTranscript(
        transcriptText: String,
        addFableDebugInfo: Boolean
    ) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true)

        Thread {
            val reportString = StringBuilder()
            val title = TermuxConstants.TERMUX_APP_NAME + " Report Issue"

            reportString.append("## Transcript\n")
            reportString
                .append("\n")
                .append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true))
            reportString.append("\n##\n")

            if (addFableDebugInfo) {
                reportString.append("\n\n").append(
                    FableUtils.getAppInfoMarkdownString(
                        mActivity,
                        FableUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES
                    )
                )
            } else {
                reportString.append("\n\n").append(
                    FableUtils.getAppInfoMarkdownString(
                        mActivity,
                        FableUtils.AppInfoMode.TERMUX_PACKAGE
                    )
                )
            }

            reportString.append("\n\n")
                .append(AndroidUtils.getDeviceInfoMarkdownString(mActivity, true))

            if (FableBootstrap.isAppPackageManagerAPT()) {
                val termuxAptInfo = FableUtils.geAPTInfoMarkdownString(mActivity)
                if (termuxAptInfo != null) {
                    reportString.append("\n\n").append(termuxAptInfo)
                }
            }

            if (addFableDebugInfo) {
                val termuxDebugInfo = FableUtils.getFableDebugMarkdownString(mActivity)
                if (termuxDebugInfo != null) {
                    reportString.append("\n\n").append(termuxDebugInfo)
                }
            }

            val userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName()
            val reportInfo = ReportInfo(
                userActionName,
                TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME,
                title
            )
            reportInfo.setReportString(reportString.toString())
            reportInfo.setReportStringSuffix(
                "\n\n" + FableUtils.getReportIssueMarkdownString(mActivity)
            )
            reportInfo.setReportSaveFileLabelAndPath(
                userActionName,
                Environment.getExternalStorageDirectory().toString() + "/" +
                    FileUtils.sanitizeFileName(
                        TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log",
                        true,
                        true
                    )
            )
            ReportActivity.startReportActivity(mActivity, reportInfo)
        }.start()
    }

    fun doPaste() {
        val session = mActivity.getCurrentSession() ?: return
        if (!session.isRunning()) return
        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null) {
            session.paste(text)
        }
    }
}
