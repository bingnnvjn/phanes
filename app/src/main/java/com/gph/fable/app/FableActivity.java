package com.gph.fable.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.gph.fable.R;
import com.gph.fable.app.api.file.FileReceiverActivity;
import com.gph.fable.app.terminal.FableActivityRootView;
import com.gph.fable.app.terminal.FableTerminalPalette;
import com.gph.fable.app.terminal.FableTerminalView;
import com.gph.fable.app.terminal.FableTerminalSessionActivityClient;
import com.gph.fable.app.terminal.io.FableTerminalExtraKeys;
import com.gph.fable.shared.activities.ReportActivity;
import com.gph.fable.shared.activity.ActivityUtils;
import com.gph.fable.shared.activity.media.AppCompatActivityUtils;
import com.gph.fable.shared.data.IntentUtils;
import com.gph.fable.shared.android.PermissionUtils;
import com.gph.fable.shared.data.DataUtils;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.gph.fable.app.activities.HelpActivity;
import com.gph.fable.app.activities.SettingsActivity;
import com.gph.fable.shared.termux.crash.FableCrashUtils;
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences;
import com.gph.fable.app.terminal.FableShellSessionsListViewController;
import com.gph.fable.app.terminal.RecentSessionsListViewController;
import com.gph.fable.app.session.RecentSessionStore;
import com.gph.fable.app.session.RecentSessionStore.RecentSession;
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession;
import com.gph.fable.app.terminal.io.TerminalToolbarViewPager;
import com.gph.fable.app.terminal.FableTerminalViewClient;
import com.gph.fable.shared.termux.extrakeys.ExtraKeysView;
import com.gph.fable.shared.termux.interact.TextInputDialogUtils;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.termux.FableUtils;
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties;
import com.gph.fable.shared.termux.theme.FableThemeUtils;
import com.gph.fable.shared.theme.NightMode;
import com.gph.fable.shared.theme.ThemeUtils;
import com.gph.fable.shared.view.ViewUtils;
import com.gph.fable.core.TerminalSession;
import com.gph.fable.core.TerminalSessionClient;
import com.gph.fable.view.FableInputTerminalView;
import com.gph.fable.view.TerminalView;
import com.gph.fable.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class FableActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link FableService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    FableService mFableService;

    /**
     * The {@link TerminalView} shown in  {@link FableActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     * 工单 15：Fable 渲染容器（每会话一个 fable-render 适配器 + SurfaceView）。
     * 主终端正文由它承载；{@link #mTerminalView} 继续承担输入/手势/选择。
     */
    FableTerminalView mFableTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link FableActivity}.
     */
    FableTerminalViewClient mFableTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link FableActivity}.
     */
    FableTerminalSessionActivityClient mFableTerminalSessionActivityClient;

    /**
     * Fable app shared preferences manager.
     */
    private FableAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private FableAppSharedProperties mProperties;

    /**
     * The root view of the {@link FableActivity}.
     */
    FableActivityRootView mFableActivityRootView;

    /**
     * The space at the bottom of {@link @mFableActivityRootView} of the {@link FableActivity}.
     */
    View mFableActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    FableTerminalExtraKeys mFableTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    FableShellSessionsListViewController mFableShellSessionListViewController;

    /**
     * 最近会话列表控制器（工单 04：进程被杀后一键重开）。
     */
    public RecentSessionsListViewController mRecentSessionsListViewController;

    /**
     * The {@link FableActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mFableActivityBroadcastReceiver = new FableActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /** 工单 05：通知权限拒绝提示只弹一次（进程内），避免每次回前台打扰。 */
    private static boolean sNotificationPermissionDeniedToastShown = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link FableActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "FableActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Fable app SharedProperties from disk
        mProperties = FableAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_fable);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = FableAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mFableActivityRootView = findViewById(R.id.activity_fable_root_view);
        mFableActivityRootView.setActivity(this);
        mFableActivityBottomSpaceView = findViewById(R.id.activity_fable_bottom_space_view);
        mFableActivityRootView.setOnApplyWindowInsetsListener(new FableActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setFableTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link FableService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, FableService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"FableActivity failed to start FableService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_fable_service_start_failed_bg : R.string.error_fable_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        FableUtils.sendFableOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mFableTerminalSessionActivityClient != null)
            mFableTerminalSessionActivityClient.onStart();

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onStart();

        // 工单 05：Android 13+ 通知运行时权限。拒绝/被撤销只隐藏前台服务通知，
        // 不中断会话（权限请求策略见 PermissionUtils#shouldRequestNotificationPermission）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 权限被撤销/此前拒绝：不打断会话，仅降级提示一次（进程内），用户可去系统设置开启。
            if (!PermissionUtils.isNotificationPermissionGranted(this) &&
                PermissionUtils.isNotificationPermissionAskedBefore(this) &&
                !sNotificationPermissionDeniedToastShown) {
                sNotificationPermissionDeniedToastShown = true;
                Logger.logInfoAndShowToast(this, LOG_TAG, getString(R.string.msg_notification_permission_denied));
            }
            PermissionUtils.requestNotificationPermission(this);
        }

        // 设置页可能改了界面主题/字号，回前台时重新应用（主题变化会触发 Activity 重建，
        // 会话由前台服务持有不丢）。
        setActivityTheme();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addFableActivityRootViewGlobalLayoutListener();

        registerFableActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mFableTerminalSessionActivityClient != null)
            mFableTerminalSessionActivityClient.onResume();

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        FableCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mFableTerminalSessionActivityClient != null)
            mFableTerminalSessionActivityClient.onStop();

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onStop();

        removeFableActivityRootViewGlobalLayoutListener();

        unregisterFableActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mFableService != null) {
            // Do not leave service and session clients with references to activity.
            mFableService.unsetFableTerminalSessionClient();
            mFableService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mFableService = ((FableService.LocalBinder) service).service;

        setFableShellSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mFableService.isFableShellSessionsEmpty()) {
            if (mIsVisible) {
                FableInstaller.setupBootstrapIfNeeded(FableActivity.this, () -> {
                    if (mFableService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mFableTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mFableTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mFableTerminalSessionActivityClient.setCurrentSession(mFableTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} clients.
        mFableService.setFableTerminalSessionClient(mFableTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link FableService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadFablePropertiesFromDisk();

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        String themeMode = resolveThemeMode();

        // Update NightMode.APP_NIGHT_MODE
        FableThemeUtils.setAppNightMode(themeMode);

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, themeMode, true);

        // 状态栏与外壳主题一致：浅色白底深图标，深色黑底浅图标。
        boolean dark = ThemeUtils.shouldEnableDarkTheme(this, themeMode);
        applyStatusBarStyle(dark ? 0xFF000000 : 0xFFFFFFFF);
    }

    /** 状态栏颜色与图标取色按背景亮度适配（避免浅色模式白底白字不可见）。 */
    public void applyStatusBarStyle(int backgroundColor) {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(backgroundColor);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (FableTerminalPalette.isDarkBackground(backgroundColor)) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** 界面主题：设置项优先（三选一），未设置过回退 termux.properties night-mode。 */
    private String resolveThemeMode() {
        return FableThemeUtils.getThemeMode(this, mProperties != null ? mProperties.getNightMode() : null);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_fable_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addFableActivityRootViewGlobalLayoutListener() {
        getFableActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getFableActivityRootView());
    }

    public void removeFableActivityRootViewGlobalLayoutListener() {
        if (getFableActivityRootView() != null)
            getFableActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getFableActivityRootView());
    }



    private void setFableTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mFableTerminalSessionActivityClient = new FableTerminalSessionActivityClient(this);
        mFableTerminalViewClient = new FableTerminalViewClient(this, mFableTerminalSessionActivityClient);

        // Set fable render container and inner input terminal view
        mFableTerminalView = findViewById(R.id.fable_terminal_view);
        mTerminalView = findViewById(R.id.terminal_input_view);
        mFableTerminalView.setInputView((FableInputTerminalView) mTerminalView);
        mTerminalView.setTerminalViewClient(mFableTerminalViewClient);

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onCreate();

        if (mFableTerminalSessionActivityClient != null)
            mFableTerminalSessionActivityClient.onCreate();
    }

    private void setFableShellSessionsListView() {
        ListView fableShellSessionsListView = findViewById(R.id.terminal_sessions_list);
        mFableShellSessionListViewController = new FableShellSessionsListViewController(this, mFableService.getFableShellSessions());
        fableShellSessionsListView.setAdapter(mFableShellSessionListViewController);
        fableShellSessionsListView.setOnItemClickListener(mFableShellSessionListViewController);
        fableShellSessionsListView.setOnItemLongClickListener(mFableShellSessionListViewController);

        ListView recentSessionsListView = findViewById(R.id.recent_sessions_list);
        mRecentSessionsListViewController = new RecentSessionsListViewController(this, recentSessionsListView,
            findViewById(R.id.recent_sessions_header));
        recentSessionsListView.setAdapter(mRecentSessionsListViewController);
        recentSessionsListView.setOnItemClickListener(mRecentSessionsListViewController);
        recentSessionsListView.setOnItemLongClickListener(mRecentSessionsListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mFableTerminalExtraKeys = new FableTerminalExtraKeys(this, mTerminalView,
            mFableTerminalViewClient, mFableTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mFableTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mFableTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mFableTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(FableActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mFableTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mFableTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mFableTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            super.onBackPressed();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!FableActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(FableActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mFableTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mFableTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mFableTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mFableTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mFableTerminalSessionActivityClient != null)
                mFableTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    FableActivity.this, requestCode, true, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(FableActivity.this, LOG_TAG,
                            getString(com.gph.fable.shared.R.string.msg_storage_permission_granted_on_request));

                    FableInstaller.setupStorageSymlinks(FableActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(FableActivity.this, LOG_TAG,
                            getString(com.gph.fable.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_NOTIFICATION_PERMISSION) {
            if (!PermissionUtils.isNotificationPermissionGranted(this) && !sNotificationPermissionDeniedToastShown) {
                sNotificationPermissionDeniedToastShown = true;
                Logger.logInfoAndShowToast(this, LOG_TAG, getString(R.string.msg_notification_permission_denied));
            }
            return;
        }
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public FableActivityRootView getFableActivityRootView() {
        return mFableActivityRootView;
    }

    public View getFableActivityBottomSpaceView() {
        return mFableActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public FableTerminalExtraKeys getFableTerminalExtraKeys() {
        return mFableTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void fableShellSessionListNotifyUpdated() {
        if (mFableShellSessionListViewController != null)
            mFableShellSessionListViewController.notifyDataSetChanged();
        if (mRecentSessionsListViewController != null)
            mRecentSessionsListViewController.reload();
    }

    /** 一键重开最近会话（新 shell 进入记录目录，失效回退 $HOME）。 */
    public void reopenRecentSession(RecentSession recentSession) {
        if (recentSession == null || mFableTerminalSessionActivityClient == null) return;
        String workDir = RecentSessionStore.resolveWorkingDirectory(
            recentSession.workingDirectory, TermuxConstants.TERMUX_HOME_DIR_PATH);
        FableService service = getFableService();
        if (service == null) return;
        FableShellSession newSession = service.createFableShellSession(null, null, null, workDir, false, null);
        if (newSession == null) return;
        recordRecentSession(workDir);
        mFableTerminalSessionActivityClient.setCurrentSession(newSession.getTerminalSession());
        getDrawer().closeDrawers();
    }

    /** 记录最近会话并刷新抽屉列表（由客户端在会话切换/创建时调用）。 */
    public void recordRecentSession(String workingDirectory) {
        RecentSessionStore.record(this, workingDirectory);
        if (mRecentSessionsListViewController != null)
            mRecentSessionsListViewController.reload();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public FableService getFableService() {
        return mFableService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public FableTerminalView getFableTerminalView() {
        return mFableTerminalView;
    }

    public FableTerminalViewClient getFableTerminalViewClient() {
        return mFableTerminalViewClient;
    }

    public FableTerminalSessionActivityClient getFableTerminalSessionClient() {
        return mFableTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public FableAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public FableAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateFableActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        // 工单 05：targetSdk 34+ 对动态注册的非导出接收器，发送方须用显式广播（仅本 App 内部使用）。
        stylingIntent.setPackage(context.getPackageName());
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerFableActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        // 工单 05：targetSdk 34+ 动态注册非系统广播必须指定导出标志；本接收器只收 App 内部广播。
        ContextCompat.registerReceiver(this, mFableActivityBroadcastReceiver, intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterFableActivityBroadcastReceiver() {
        unregisterReceiver(mFableActivityBroadcastReceiver);
    }

    private void fixFableActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class FableActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixFableActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        FableCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mFableTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE（设置项优先）
            FableThemeUtils.setAppNightMode(resolveThemeMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mFableTerminalSessionActivityClient != null)
            mFableTerminalSessionActivityClient.onReloadActivityStyling();

        if (mFableTerminalViewClient != null)
            mFableTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            FableActivity.this.recreate();
        }
    }



    public static void startFableActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, FableActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
