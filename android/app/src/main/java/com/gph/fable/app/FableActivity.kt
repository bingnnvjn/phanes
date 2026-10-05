package com.gph.fable.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.ViewPager
import com.gph.fable.R
import com.gph.fable.app.activities.HelpActivity
import com.gph.fable.app.activities.SettingsActivity
import com.gph.fable.app.api.file.FileReceiverActivity
import com.gph.fable.app.session.RecentSessionStore
import com.gph.fable.app.session.RecentSessionStore.RecentSession
import com.gph.fable.app.terminal.FableActivityRootView
import com.gph.fable.app.terminal.FableMorePanel
import com.gph.fable.app.terminal.FableMorePanelLogic
import com.gph.fable.app.terminal.FableShellSessionsListViewController
import com.gph.fable.app.terminal.FableTerminalPalette
import com.gph.fable.app.terminal.FableTerminalSessionActivityClient
import com.gph.fable.app.terminal.FableTerminalView
import com.gph.fable.app.terminal.FableTerminalViewClient
import com.gph.fable.app.terminal.RecentSessionsListViewController
import com.gph.fable.app.terminal.io.FableTerminalExtraKeys
import com.gph.fable.app.terminal.io.TerminalToolbarViewPager
import com.gph.fable.core.TerminalSession
import com.gph.fable.shared.activities.ReportActivity
import com.gph.fable.shared.activity.ActivityUtils
import com.gph.fable.shared.activity.media.AppCompatActivityUtils
import com.gph.fable.shared.android.PermissionUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.gph.fable.shared.termux.crash.FableCrashUtils
import com.gph.fable.shared.termux.extrakeys.ExtraKeysView
import com.gph.fable.shared.termux.interact.TextInputDialogUtils
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession
import com.gph.fable.shared.termux.theme.FableThemeUtils
import com.gph.fable.shared.theme.ThemeUtils
import com.gph.fable.shared.view.ViewUtils
import com.gph.fable.view.FableInputTerminalView
import com.gph.fable.view.TerminalView
import java.util.Arrays
import kotlin.math.roundToInt

/**
 * A terminal emulator activity.
 *
 * The implementation is kept in Kotlin while retaining the public Java ABI used by
 * services, plugins, and the launcher entry point.
 */
class FableActivity : AppCompatActivity(), ServiceConnection {

    @JvmField
    var mFableService: FableService? = null

    @JvmField
    var mTerminalView: TerminalView? = null

    @JvmField
    var mFableTerminalView: FableTerminalView? = null

    @JvmField
    var mFableTerminalViewClient: FableTerminalViewClient? = null

    @JvmField
    var mFableTerminalSessionActivityClient: FableTerminalSessionActivityClient? = null

    @JvmField
    var mPreferences: FableAppSharedPreferences? = null

    @JvmField
    var mProperties: FableAppSharedProperties? = null

    @JvmField
    var mFableActivityRootView: FableActivityRootView? = null

    @JvmField
    var mFableActivityBottomSpaceView: View? = null

    @JvmField
    var mExtraKeysView: ExtraKeysView? = null

    @JvmField
    var mFableTerminalExtraKeys: FableTerminalExtraKeys? = null

    @JvmField
    var mFableShellSessionListViewController: FableShellSessionsListViewController? = null

    @JvmField
    var mRecentSessionsListViewController: RecentSessionsListViewController? = null

    private val mFableActivityBroadcastReceiver: BroadcastReceiver = FableActivityBroadcastReceiver()

    @JvmField
    var mLastToast: Toast? = null

    private var mIsVisible = false
    private var mIsOnResumeAfterOnCreate = false

    private var mIsActivityRecreated = false
    private var mIsInvalidState = false

    private var mNavBarHeight = 0
    private var mTerminalToolbarDefaultHeight = 0f

    public override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        mIsOnResumeAfterOnCreate = true

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false)
        }

        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        mProperties = FableAppSharedProperties.getProperties()
        reloadProperties()

        setActivityTheme()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fable)

        mPreferences = FableAppSharedPreferences.build(this, true)
        if (mPreferences == null) {
            mIsInvalidState = true
            return
        }

        setMargins()

        mFableActivityRootView = findViewById(R.id.activity_fable_root_view)
        mFableActivityRootView!!.setActivity(this)
        mFableActivityBottomSpaceView = findViewById(R.id.activity_fable_bottom_space_view)
        mFableActivityRootView!!.setOnApplyWindowInsetsListener(FableActivityRootView.WindowInsetsListener())

        val content = findViewById<View>(android.R.id.content)
        content.setOnApplyWindowInsetsListener { _, insets ->
            mNavBarHeight = insets.systemWindowInsetBottom
            insets
        }

        if (mProperties!!.isUsingFullScreen()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setFableTerminalViewAndClients()
        setTerminalToolbarView(savedInstanceState)
        setSettingsButtonView()
        setMoreButtonView()
        setNewSessionButtonView()
        setToggleKeyboardView()
        registerForContextMenu(mTerminalView!!)

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        try {
            val serviceIntent = Intent(this, FableService::class.java)
            startService(serviceIntent)
            if (!bindService(serviceIntent, this, 0)) {
                throw RuntimeException("bindService() failed")
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "FableActivity failed to start FableService", e)
            Logger.showToast(
                this,
                getString(
                    if (e.message?.contains("app is in background") == true) {
                        R.string.error_fable_service_start_failed_bg
                    } else {
                        R.string.error_fable_service_start_failed_general
                    }
                ),
                true
            )
            mIsInvalidState = true
            return
        }

        FableUtils.sendFableOpenedBroadcast(this)
    }

    public override fun onStart() {
        super.onStart()
        Logger.logDebug(LOG_TAG, "onStart")
        if (mIsInvalidState) return

        mIsVisible = true
        mFableTerminalSessionActivityClient?.onStart()
        mFableTerminalViewClient?.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionUtils.isNotificationPermissionGranted(this) &&
                PermissionUtils.isNotificationPermissionAskedBefore(this) &&
                !sNotificationPermissionDeniedToastShown
            ) {
                sNotificationPermissionDeniedToastShown = true
                Logger.logInfoAndShowToast(this, LOG_TAG, getString(R.string.msg_notification_permission_denied))
            }
            PermissionUtils.requestNotificationPermission(this)
        }

        setActivityTheme()
        if (mPreferences!!.isTerminalMarginAdjustmentEnabled()) {
            addFableActivityRootViewGlobalLayoutListener()
        }
        registerFableActivityBroadcastReceiver()
    }

    public override fun onResume() {
        super.onResume()
        Logger.logVerbose(LOG_TAG, "onResume")
        if (mIsInvalidState) return

        mFableTerminalSessionActivityClient?.onResume()
        mFableTerminalViewClient?.onResume()
        FableCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)
        mIsOnResumeAfterOnCreate = false
    }

    override fun onStop() {
        super.onStop()
        Logger.logDebug(LOG_TAG, "onStop")
        if (mIsInvalidState) return

        mIsVisible = false
        mFableTerminalSessionActivityClient?.onStop()
        mFableTerminalViewClient?.onStop()
        removeFableActivityRootViewGlobalLayoutListener()
        unregisterFableActivityBroadcastReceiver()
        getDrawer().closeDrawers()
    }

    public override fun onDestroy() {
        super.onDestroy()
        Logger.logDebug(LOG_TAG, "onDestroy")
        if (mIsInvalidState) return

        mFableService?.let {
            it.unsetFableTerminalSessionClient()
            mFableService = null
        }

        try {
            unbindService(this)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    public override fun onSaveInstanceState(@NonNull savedInstanceState: Bundle) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState")
        super.onSaveInstanceState(savedInstanceState)
        saveTerminalToolbarTextInput(savedInstanceState)
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true)
    }

    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        mFableService = (service as FableService.LocalBinder).service
        setFableShellSessionsListView()

        val intent = intent
        setIntent(null)
        val fableService = mFableService ?: return

        if (fableService.isFableShellSessionsEmpty()) {
            if (mIsVisible) {
                FableInstaller.setupBootstrapIfNeeded(this) {
                    val connectedService = mFableService ?: return@setupBootstrapIfNeeded
                    try {
                        var launchFailsafe = false
                        if (intent?.extras != null) {
                            launchFailsafe = intent.extras!!.getBoolean(
                                TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION,
                                false
                            )
                        }
                        mFableTerminalSessionActivityClient!!.addNewSession(launchFailsafe, null)
                    } catch (_: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                }
            } else {
                finishActivityIfNotFinishing()
            }
        } else {
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN == intent.action) {
                val isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                mFableTerminalSessionActivityClient!!.addNewSession(isFailSafe, null)
            } else {
                mFableTerminalSessionActivityClient!!.setCurrentSession(
                    mFableTerminalSessionActivityClient!!.getCurrentStoredSessionOrLast()
                )
            }
        }

        fableService.setFableTerminalSessionClient(mFableTerminalSessionActivityClient)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")
        finishActivityIfNotFinishing()
    }

    private fun reloadProperties() {
        mProperties!!.loadFablePropertiesFromDisk()
        mFableTerminalViewClient?.onReloadProperties()
    }

    private fun setActivityTheme() {
        val themeMode = resolveThemeMode()
        FableThemeUtils.setAppNightMode(themeMode)
        AppCompatActivityUtils.setNightMode(this, themeMode, true)
        val dark = ThemeUtils.shouldEnableDarkTheme(this, themeMode)
        applyStatusBarStyle(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    /** Apply status-bar color and icon contrast for the given background color. */
    fun applyStatusBarStyle(backgroundColor: Int) {
        val window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = backgroundColor
        var flags = window.decorView.systemUiVisibility
        if (FableTerminalPalette.isDarkBackground(backgroundColor)) {
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun resolveThemeMode(): String =
        FableThemeUtils.getThemeMode(this, mProperties?.getNightMode())

    private fun setMargins() {
        val relativeLayout = findViewById<RelativeLayout>(R.id.activity_fable_root_relative_layout)
        val marginHorizontal = mProperties!!.getTerminalMarginHorizontal()
        val marginVertical = mProperties!!.getTerminalMarginVertical()
        ViewUtils.setLayoutMarginsInDp(
            relativeLayout,
            marginHorizontal,
            marginVertical,
            marginHorizontal,
            marginVertical
        )
    }

    fun addFableActivityRootViewGlobalLayoutListener() {
        getFableActivityRootView().viewTreeObserver.addOnGlobalLayoutListener(getFableActivityRootView())
    }

    fun removeFableActivityRootViewGlobalLayoutListener() {
        mFableActivityRootView?.let {
            it.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
    }

    private fun setFableTerminalViewAndClients() {
        mFableTerminalSessionActivityClient = FableTerminalSessionActivityClient(this)
        mFableTerminalViewClient =
            FableTerminalViewClient(this, mFableTerminalSessionActivityClient!!)

        mFableTerminalView = findViewById(R.id.fable_terminal_view)
        mTerminalView = findViewById(R.id.terminal_input_view)
        mFableTerminalView!!.setInputView(mTerminalView as FableInputTerminalView)
        mTerminalView!!.setTerminalViewClient(mFableTerminalViewClient)

        mFableTerminalViewClient?.onCreate()
        mFableTerminalSessionActivityClient?.onCreate()
    }

    private fun setFableShellSessionsListView() {
        val fableShellSessionsListView = findViewById<ListView>(R.id.terminal_sessions_list)
        val service = mFableService!!
        mFableShellSessionListViewController =
            FableShellSessionsListViewController(
                this,
                service.getFableShellSessions() as MutableList<FableShellSession>
            )
        fableShellSessionsListView.adapter = mFableShellSessionListViewController
        fableShellSessionsListView.setOnItemClickListener(mFableShellSessionListViewController)
        fableShellSessionsListView.setOnItemLongClickListener(mFableShellSessionListViewController)

        val recentSessionsListView = findViewById<ListView>(R.id.recent_sessions_list)
        mRecentSessionsListViewController = RecentSessionsListViewController(
            this,
            recentSessionsListView,
            findViewById(R.id.recent_sessions_header)
        )
        recentSessionsListView.adapter = mRecentSessionsListViewController
        recentSessionsListView.setOnItemClickListener(mRecentSessionsListViewController)
        recentSessionsListView.setOnItemLongClickListener(mRecentSessionsListViewController)
    }

    private fun setTerminalToolbarView(savedInstanceState: Bundle?) {
        mFableTerminalExtraKeys = FableTerminalExtraKeys(
            this,
            mTerminalView!!,
            mFableTerminalViewClient,
            mFableTerminalSessionActivityClient
        )

        val terminalToolbarViewPager = getTerminalToolbarViewPager()!!
        if (mPreferences!!.shouldShowTerminalToolbar()) {
            terminalToolbarViewPager.visibility = View.VISIBLE
        }

        val layoutParams = terminalToolbarViewPager.layoutParams
        mTerminalToolbarDefaultHeight = layoutParams.height.toFloat()
        setTerminalToolbarHeight()

        val savedTextInput = savedInstanceState?.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)
        terminalToolbarViewPager.adapter = TerminalToolbarViewPager.PageAdapter(this, savedTextInput)
        terminalToolbarViewPager.addOnPageChangeListener(
            TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager)
        )
    }

    private fun setTerminalToolbarHeight() {
        val terminalToolbarViewPager = getTerminalToolbarViewPager() ?: return
        val layoutParams = terminalToolbarViewPager.layoutParams
        layoutParams.height = Math.round(
            mTerminalToolbarDefaultHeight *
                (mFableTerminalExtraKeys!!.getExtraKeysInfo()?.matrix?.size ?: 0) *
                mProperties!!.getTerminalToolbarHeightScaleFactor()
        ).toInt()
        terminalToolbarViewPager.layoutParams = layoutParams
    }

    fun toggleTerminalToolbar() {
        val terminalToolbarViewPager = getTerminalToolbarViewPager() ?: return
        val showNow = mPreferences!!.toogleShowTerminalToolbar()
        Logger.showToast(
            this,
            if (showNow) getString(R.string.msg_enabling_terminal_toolbar) else
                getString(R.string.msg_disabling_terminal_toolbar),
            true
        )
        terminalToolbarViewPager.visibility = if (showNow) View.VISIBLE else View.GONE
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            findViewById<EditText>(R.id.terminal_toolbar_text_input).requestFocus()
        }
    }

    private fun saveTerminalToolbarTextInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val textInputView = findViewById<EditText>(R.id.terminal_toolbar_text_input)
        if (textInputView != null) {
            val textInput = textInputView.text.toString()
            if (textInput.isNotEmpty()) {
                savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput)
            }
        }
    }

    private fun setSettingsButtonView() {
        val settingsButton = findViewById<ImageButton>(R.id.settings_button)
        settingsButton.setOnClickListener {
            ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setMoreButtonView() {
        findViewById<ImageButton>(R.id.more_button).setOnClickListener { showMorePanel() }
    }

    /** 抽屉顶部「更多」：会话/界面设置快捷面板。 */
    private fun showMorePanel() {
        FableMorePanel(this, findViewById(R.id.more_button)) { action -> onMorePanelAction(action) }.show()
    }

    private fun onMorePanelAction(action: FableMorePanelLogic.Action) {
        getDrawer().closeDrawers()
        when (action) {
            FableMorePanelLogic.Action.NEW_SESSION ->
                mFableTerminalSessionActivityClient!!.addNewSession(false, null)
            FableMorePanelLogic.Action.CLOSE_SESSION ->
                showKillSessionDialog(getCurrentSession())
            FableMorePanelLogic.Action.RENAME_SESSION ->
                mFableTerminalSessionActivityClient!!.renameSession(getCurrentSession())
            FableMorePanelLogic.Action.THEME -> showThemeDialog()
            FableMorePanelLogic.Action.FONT_SIZE -> showFontSizeDialog()
            FableMorePanelLogic.Action.SETTINGS ->
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showThemeDialog() {
        val entries = resources.getStringArray(R.array.theme_mode_entries)
        val selected = FableMorePanelLogic.themeIndex(mPreferences!!.getThemeMode())
        AlertDialog.Builder(this)
            .setTitle(R.string.action_theme)
            .setSingleChoiceItems(entries, selected) { dialog, which ->
                dialog.dismiss()
                applyThemeMode(FableMorePanelLogic.themeValue(which))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyThemeMode(mode: String) {
        mPreferences!!.setThemeMode(mode)
        FableThemeUtils.setAppNightMode(mode)
        AppCompatActivityUtils.setNightMode(this, mode, true)
        recreate()
    }

    /** 字号快捷滑杆：滑动即生效，取消则还原原值。 */
    private fun showFontSizeDialog() {
        val startPx = mPreferences!!.getFontSize()
        val seekBar = SeekBar(this).apply {
            max = FableMorePanelLogic.MAX_FONT_DP - FableMorePanelLogic.MIN_FONT_DP
            progress = FableMorePanelLogic.clampFontDp(mPreferences!!.getFontSizeDp(this@FableActivity)) -
                FableMorePanelLogic.MIN_FONT_DP
        }
        val valueView = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(
                R.string.font_size_dp_value,
                FableMorePanelLogic.MIN_FONT_DP + seekBar.progress
            )
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val sizeDp = FableMorePanelLogic.MIN_FONT_DP + progress
                mPreferences!!.setFontSizeDp(this@FableActivity, sizeDp)
                mTerminalView!!.setTextSize(mPreferences!!.getFontSize())
                valueView.text = getString(R.string.font_size_dp_value, sizeDp)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(8f), dpToPx(24f), 0)
            addView(valueView)
            addView(seekBar)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_font_size)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                mPreferences!!.setFontSize(startPx)
                mTerminalView!!.setTextSize(startPx)
            }
            .show()
    }

    private fun dpToPx(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun setNewSessionButtonView() {
        val newSessionButton = findViewById<View>(R.id.new_session_button)
        newSessionButton.setOnClickListener {
            mFableTerminalSessionActivityClient!!.addNewSession(false, null)
        }
        newSessionButton.setOnLongClickListener {
            TextInputDialogUtils.textInput(
                this,
                R.string.title_create_named_session,
                null,
                R.string.action_create_named_session_confirm,
                { text -> mFableTerminalSessionActivityClient!!.addNewSession(false, text) },
                R.string.action_new_session_failsafe,
                { text -> mFableTerminalSessionActivityClient!!.addNewSession(true, text) },
                -1,
                null,
                null
            )
            true
        }
    }

    private fun setToggleKeyboardView() {
        findViewById<View>(R.id.toggle_keyboard_button).setOnClickListener {
            mFableTerminalViewClient!!.onToggleSoftKeyboardRequest()
            getDrawer().closeDrawers()
        }
        findViewById<View>(R.id.toggle_keyboard_button).setOnLongClickListener {
            toggleTerminalToolbar()
            true
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onBackPressed() {
        if (mTerminalView?.isSelectingText() == true) {
            // 自绘浮条取代系统 ActionMode 后，BACK 需在这里收起选择。
            mTerminalView!!.forceStopTextSelectionMode()
            return
        }
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    fun finishActivityIfNotFinishing() {
        if (!isFinishing) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    fun showToast(text: String?, longDuration: Boolean) {
        if (text.isNullOrEmpty()) return
        mLastToast?.cancel()
        mLastToast = Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mLastToast!!.setGravity(Gravity.TOP, 0, 0)
        mLastToast!!.show()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        val currentSession = getCurrentSession() ?: return
        // 「选择菜单」不再有「更多…」，改由右键/快捷键直接开上下文菜单时留存当前选区。
        mTerminalView!!.captureSelectedTextForContextMenu()
        val autoFillEnabled = mTerminalView!!.isAutoFillEnabled()

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(mTerminalView!!.getStoredSelectedText())) {
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        }
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(
            Menu.NONE,
            CONTEXT_MENU_KILL_PROCESS_ID,
            Menu.NONE,
            resources.getString(R.string.action_kill_process, currentSession.pid)
        ).isEnabled = currentSession.isRunning
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(
            Menu.NONE,
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON,
            Menu.NONE,
            R.string.action_toggle_keep_screen_on
        ).setCheckable(true).setChecked(mPreferences!!.shouldKeepScreenOn())
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView!!.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = getCurrentSession()
        return when (item.itemId) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                mFableTerminalViewClient!!.showUrlSelection()
                true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mFableTerminalViewClient!!.shareSessionTranscript()
                true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mFableTerminalViewClient!!.shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView!!.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView!!.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_STYLING_ID -> {
                showStylingDialog()
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
                true
            }
            CONTEXT_MENU_REPORT_ID -> {
                mFableTerminalViewClient!!.reportIssueFromTranscript()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        mTerminalView!!.onContextMenuClosed(menu)
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return
        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.title_confirm_kill_process)
            .setPositiveButton(android.R.string.yes) { dialog, _ ->
                dialog.dismiss()
                session.finishIfRunning()
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            showToast(resources.getString(R.string.msg_terminal_reset), true)
            mFableTerminalSessionActivityClient?.onResetTerminalSession()
        }
    }

    private fun showStylingDialog() {
        val stylingIntent = Intent().apply {
            setClassName(
                TermuxConstants.TERMUX_STYLING_PACKAGE_NAME,
                TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME
            )
        }
        try {
            startActivity(stylingIntent)
        } catch (_: ActivityNotFoundException) {
            showStylingNotInstalledDialog()
        } catch (_: IllegalArgumentException) {
            showStylingNotInstalledDialog()
        }
    }

    private fun showStylingNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.error_styling_not_installed))
            .setPositiveButton(R.string.action_styling_install) { _, _ ->
                ActivityUtils.startActivity(
                    this,
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleKeepScreenOn() {
        if (mTerminalView!!.keepScreenOn) {
            mTerminalView!!.keepScreenOn = false
            mPreferences!!.setKeepScreenOn(false)
        } else {
            mTerminalView!!.keepScreenOn = true
            mPreferences!!.setKeepScreenOn(true)
        }
    }

    fun requestStoragePermission(isPermissionCallback: Boolean) {
        Thread {
            val requestCode =
                if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

            if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    this,
                    requestCode,
                    true,
                    !isPermissionCallback
                )
            ) {
                if (isPermissionCallback) {
                    Logger.logInfoAndShowToast(
                        this,
                        LOG_TAG,
                        getString(com.gph.fable.shared.R.string.msg_storage_permission_granted_on_request)
                    )
                }
                FableInstaller.setupStorageSymlinks(this)
            } else if (isPermissionCallback) {
                Logger.logInfoAndShowToast(
                    this,
                    LOG_TAG,
                    getString(com.gph.fable.shared.R.string.msg_storage_permission_not_granted_on_request)
                )
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(
            LOG_TAG,
            "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: ${IntentUtils.getIntentString(data)}"
        )
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(
            LOG_TAG,
            "onRequestPermissionsResult: requestCode: $requestCode, permissions: " +
                "${Arrays.toString(permissions)}, grantResults: ${Arrays.toString(grantResults)}"
        )
        if (requestCode == PermissionUtils.REQUEST_NOTIFICATION_PERMISSION) {
            if (!PermissionUtils.isNotificationPermissionGranted(this) &&
                !sNotificationPermissionDeniedToastShown
            ) {
                sNotificationPermissionDeniedToastShown = true
                Logger.logInfoAndShowToast(this, LOG_TAG, getString(R.string.msg_notification_permission_denied))
            }
            return
        }
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    val navBarHeight: Int
        get() = mNavBarHeight

    fun getFableActivityRootView(): FableActivityRootView = mFableActivityRootView!!

    fun getFableActivityBottomSpaceView(): View? = mFableActivityBottomSpaceView

    fun getExtraKeysView(): ExtraKeysView? = mExtraKeysView

    fun getFableTerminalExtraKeys(): FableTerminalExtraKeys = mFableTerminalExtraKeys!!

    fun setExtraKeysView(extraKeysView: ExtraKeysView) {
        mExtraKeysView = extraKeysView
    }

    fun getDrawer(): DrawerLayout = findViewById(R.id.drawer_layout)

    fun getTerminalToolbarViewPager(): ViewPager? = findViewById(R.id.terminal_toolbar_view_pager)

    fun getTerminalToolbarDefaultHeight(): Float = mTerminalToolbarDefaultHeight

    fun isTerminalViewSelected(): Boolean = getTerminalToolbarViewPager()!!.currentItem == 0

    fun isTerminalToolbarTextInputViewSelected(): Boolean =
        getTerminalToolbarViewPager()!!.currentItem == 1

    fun fableShellSessionListNotifyUpdated() {
        mFableShellSessionListViewController?.notifyDataSetChanged()
        mRecentSessionsListViewController?.reload()
    }

    fun reopenRecentSession(recentSession: RecentSession?) {
        if (recentSession == null || mFableTerminalSessionActivityClient == null) return
        val workDir = RecentSessionStore.resolveWorkingDirectory(
            recentSession.workingDirectory,
            TermuxConstants.TERMUX_HOME_DIR_PATH
        )
        val service = getFableService() ?: return
        val newSession = service.createFableShellSession(null, null, null, workDir, false, null) ?: return
        recordRecentSession(workDir)
        mFableTerminalSessionActivityClient!!.setCurrentSession(newSession.terminalSession)
        getDrawer().closeDrawers()
    }

    fun recordRecentSession(workingDirectory: String?) {
        if (workingDirectory == null) return
        RecentSessionStore.record(this, workingDirectory)
        mRecentSessionsListViewController?.reload()
    }

    fun isVisible(): Boolean = mIsVisible

    fun isOnResumeAfterOnCreate(): Boolean = mIsOnResumeAfterOnCreate

    fun isActivityRecreated(): Boolean = mIsActivityRecreated

    fun getFableService(): FableService? = mFableService

    fun getTerminalView(): TerminalView = mTerminalView!!

    fun getFableTerminalView(): FableTerminalView = mFableTerminalView!!

    fun getFableTerminalViewClient(): FableTerminalViewClient = mFableTerminalViewClient!!

    fun getFableTerminalSessionClient(): FableTerminalSessionActivityClient =
        mFableTerminalSessionActivityClient!!

    @Nullable
    fun getCurrentSession(): TerminalSession? = mTerminalView?.getCurrentSession()

    fun getPreferences(): FableAppSharedPreferences = mPreferences!!

    fun getProperties(): FableAppSharedProperties = mProperties!!

    private fun registerFableActivityBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
            addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        }
        ContextCompat.registerReceiver(
            this,
            mFableActivityBroadcastReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterFableActivityBroadcastReceiver() {
        unregisterReceiver(mFableActivityBroadcastReceiver)
    }

    private fun fixFableActivityBroadcastReceiverIntent(intent: Intent?) {
        if (intent == null) return
        val extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if (extraReloadStyle == "storage") {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.action = TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS
        }
    }

    private inner class FableActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null || !mIsVisible) return

            fixFableActivityBroadcastReceiverIntent(intent)
            when (intent.action) {
                TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                    Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                    FableCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                }
                TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                    Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                    reloadActivityStyling(
                        intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true)
                    )
                }
                TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                    Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                    requestStoragePermission(false)
                }
            }
        }
    }

    private fun reloadActivityStyling(recreateActivity: Boolean) {
        if (mProperties != null) {
            reloadProperties()
            mExtraKeysView?.let {
                it.setButtonTextAllCaps(mProperties!!.shouldExtraKeysTextBeAllCaps())
                it.reload(mFableTerminalExtraKeys!!.getExtraKeysInfo(), mTerminalToolbarDefaultHeight)
            }
            FableThemeUtils.setAppNightMode(resolveThemeMode())
        }

        setMargins()
        setTerminalToolbarHeight()
        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)
        mFableTerminalSessionActivityClient?.onReloadActivityStyling()
        mFableTerminalViewClient?.onReloadActivityStyling()

        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            recreate()
        }
    }

    companion object {
        private const val CONTEXT_MENU_SELECT_URL_ID = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_STYLING_ID = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_REPORT_ID = 9

        private const val ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input"
        private const val ARG_ACTIVITY_RECREATED = "activity_recreated"
        private const val LOG_TAG = "FableActivity"

        private var sNotificationPermissionDeniedToastShown = false

        @JvmStatic
        fun updateFableActivityStyling(context: Context, recreateActivity: Boolean) {
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE).apply {
                setPackage(context.packageName)
                putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            }
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startFableActivity(@NonNull context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        @JvmStatic
        fun newInstance(@NonNull context: Context): Intent =
            Intent(context, FableActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }
}
