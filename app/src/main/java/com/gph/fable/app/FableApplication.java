package com.gph.fable.app;

import android.app.Application;
import android.content.Context;

import com.gph.fable.BuildConfig;
import com.gph.fable.app.terminal.FableDiagnostics;
import com.gph.fable.shared.errors.Error;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.termux.FableBootstrap;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.crash.FableCrashUtils;
import com.gph.fable.shared.termux.file.FableFileUtils;
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences;
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties;
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment;
import com.gph.fable.shared.termux.shell.am.FableAmSocketServer;
import com.gph.fable.shared.termux.shell.FableShellManager;
import com.gph.fable.shared.termux.theme.FableThemeUtils;
import com.gph.fable.view.TerminalView;

public class FableApplication extends Application {

    private static final String LOG_TAG = "FableApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // 工单 26：主终端只显示 Bash 登录提示——幂等截空 $PREFIX/etc/motd
        // （覆盖已存在/迁移的 prefix；安装完成路径另有调用）。
        FableInstaller.suppressFableMotd(context);

        // 渲染器诊断文件日志（跨 uid 无法通过 logcat 读取）。
        FableDiagnostics.init(context);
        // 选择流程诊断回调（选择空白根因定位）。
        TerminalView.setDiagnosticListener(FableDiagnostics::append);

        // Set crash handler for the app
        FableCrashUtils.setDefaultCrashHandler(this);
        // 崩溃也写进诊断文件（选择空白若伴随崩溃，下轮日志直接带堆栈）。
        final Thread.UncaughtExceptionHandler crashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringBuilder stackTrace = new StringBuilder(
                "CRASH thread=" + thread.getName() + " " + throwable);
            for (StackTraceElement element : throwable.getStackTrace()) {
                stackTrace.append("\n  at ").append(element);
            }
            FableDiagnostics.append(stackTrace.toString());
            if (crashHandler != null) crashHandler.uncaughtException(thread, throwable);
        });

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set FableBootstrap.TERMUX_APP_PACKAGE_MANAGER and FableBootstrap.TERMUX_APP_PACKAGE_VARIANT
        FableBootstrap.setFablePackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        FableAppSharedProperties properties = FableAppSharedProperties.init(context);

        // Init app wide shell manager
        FableShellManager shellManager = FableShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        FableThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = FableFileUtils.isFableFilesDirectoryAccessible(this, true, true);
        boolean isFableFilesDirectoryAccessible = error == null;
        if (isFableFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Fable files directory is accessible");
            /*
            error = FableFileUtils.isAppsFableAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            FableAmSocketServer.setupFableAmSocketServer(context);
             */
        } else {
            Logger.logErrorExtended(LOG_TAG, "Fable files directory is not accessible\n" + error);
        }

        // Init FableShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        FableShellEnvironment.init(this);

        if (isFableFilesDirectoryAccessible) {
            FableShellEnvironment.writeEnvironmentToFile(this);
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        FableAppSharedPreferences preferences = FableAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(context, preferences.getLogLevel());
    }

}
