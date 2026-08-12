package com.gph.fable.shared.termux.shell.am;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.errors.Error;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.net.socket.local.LocalClientSocket;
import com.gph.fable.shared.net.socket.local.LocalServerSocket;
import com.gph.fable.shared.net.socket.local.LocalSocketManager;
import com.gph.fable.shared.net.socket.local.LocalSocketManagerClientBase;
import com.gph.fable.shared.net.socket.local.LocalSocketRunConfig;
import com.gph.fable.shared.shell.am.AmSocketServerRunConfig;
import com.gph.fable.shared.shell.am.AmSocketServer;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.crash.FableCrashUtils;
import com.gph.fable.shared.termux.plugins.FablePluginUtils;
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties;
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants;
import com.gph.fable.shared.termux.shell.command.environment.FableAppShellEnvironment;

/**
 * A wrapper for {@link AmSocketServer} for termux-app usage.
 *
 * The static {@link #fableAmSocketServer} variable stores the {@link LocalSocketManager} for the
 * {@link AmSocketServer}.
 *
 * The {@link FableAmSocketServerClient} extends the {@link AmSocketServer.AmSocketServerClient}
 * class to also show plugin error notifications for errors and disallowed client connections in
 * addition to logging the messages to logcat, which are only logged by {@link LocalSocketManagerClientBase}
 * if log level is debug or higher for privacy issues.
 *
 * It uses a filesystem socket server with the socket file at
 * {@link TermuxConstants.TERMUX_APP#TERMUX_AM_SOCKET_FILE_PATH}. It would normally only allow
 * processes belonging to the termux user and root user to connect to it. If commands are sent by the
 * root user, then the am commands executed will be run as the termux user and its permissions,
 * capabilities and selinux context instead of root.
 *
 * The `$PREFIX/bin/termux-am` client connects to the server via `$PREFIX/bin/termux-am-socket` to
 * run the am commands. It provides similar functionality to "$PREFIX/bin/am"
 * (and "/system/bin/am"), but should be faster since it does not require starting a dalvik vm for
 * every command as done by "am" via termux/TermuxAm.
 *
 * The server is started by termux-app Application class but is not started if
 * {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER} is `false` which can be done by
 * adding the prop with value "false" to the "~/.termux/termux.properties" file. Changes
 * require termux-app to be force stopped and restarted.
 *
 * The current state of the server can be checked with the
 * {@link FableAppShellEnvironment#ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED} env variable, which is exported
 * for all shell sessions and tasks.
 *
 * https://github.com/termux/termux-am-socket
 * https://github.com/termux/TermuxAm
 */
public class FableAmSocketServer {

    public static final String LOG_TAG = "FableAmSocketServer";

    public static final String TITLE = "FableAm";

    /** The static instance for the {@link FableAmSocketServer} {@link LocalSocketManager}. */
    private static LocalSocketManager fableAmSocketServer;

    /** Whether {@link FableAmSocketServer} is enabled and running or not. */
    @Keep
    protected static Boolean TERMUX_APP_AM_SOCKET_SERVER_ENABLED;

    /**
     * Setup the {@link AmSocketServer} {@link LocalServerSocket} and start listening for
     * new {@link LocalClientSocket} if enabled.
     *
     * @param context The {@link Context} for {@link LocalSocketManager}.
     */
    public static void setupFableAmSocketServer(@NonNull Context context) {
        // Start termux-am-socket server if enabled by user
        boolean enabled = false;
        if (FableAppSharedProperties.getProperties().shouldRunFableAmSocketServer()) {
            Logger.logDebug(LOG_TAG, "Starting " + TITLE + " socket server since its enabled");
            start(context);
            if (fableAmSocketServer != null && fableAmSocketServer.isRunning()) {
                enabled = true;
                Logger.logDebug(LOG_TAG, TITLE + " socket server successfully started");
            }
        } else {
            Logger.logDebug(LOG_TAG, "Not starting " + TITLE + " socket server since its not enabled");
        }

        // Once termux-app has started, the server state must not be changed since the variable is
        // exported in shell sessions and tasks and if state is changed, then env of older shells will
        // retain invalid value. User should force stop the app to update state after changing prop.
        TERMUX_APP_AM_SOCKET_SERVER_ENABLED = enabled;
        FableAppShellEnvironment.updateFableAppAMSocketServerEnabled(context);
    }

    /**
     * Create the {@link AmSocketServer} {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     */
    public static synchronized void start(@NonNull Context context) {
        stop();

        AmSocketServerRunConfig amSocketServerRunConfig = new AmSocketServerRunConfig(TITLE,
            TermuxConstants.TERMUX_APP.TERMUX_AM_SOCKET_FILE_PATH, new FableAmSocketServerClient());

        fableAmSocketServer = AmSocketServer.start(context, amSocketServerRunConfig);
    }

    /**
     * Stop the {@link AmSocketServer} {@link LocalServerSocket} and stop listening for new {@link LocalClientSocket}.
     */
    public static synchronized void stop() {
        if (fableAmSocketServer != null) {
            Error error = fableAmSocketServer.stop();
            if (error != null) {
                fableAmSocketServer.onError(error);
            }
            fableAmSocketServer = null;
        }
    }
    
    /**
     * Update the state of the {@link AmSocketServer} {@link LocalServerSocket} depending on current
     * value of {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER}.
     */
    public static synchronized void updateState(@NonNull Context context) {
        FableAppSharedProperties properties = FableAppSharedProperties.getProperties();
        if (properties.shouldRunFableAmSocketServer()) {
            if (fableAmSocketServer == null) {
                Logger.logDebug(LOG_TAG, "updateState: Starting " + TITLE + " socket server");
                start(context);
            }
        } else {
            if (fableAmSocketServer != null) {
                Logger.logDebug(LOG_TAG, "updateState: Disabling " + TITLE + " socket server");
                stop();
            }
        }
    }
    
    /**
     * Get {@link #fableAmSocketServer}.
     */
    public static synchronized LocalSocketManager getFableAmSocketServer() {
        return fableAmSocketServer;
    }

    /**
     * Show an error notification on the {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID}
     * {@link TermuxConstants#TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID} with a call
     * to {@link FablePluginUtils#sendPluginCommandErrorNotification(Context, String, CharSequence, String, String)}.
     *
     * @param context The {@link Context} to send the notification with.
     * @param error The {@link Error} generated.
     * @param localSocketRunConfig The {@link LocalSocketRunConfig} for {@link LocalSocketManager}.
     * @param clientSocket The optional {@link LocalClientSocket} for which the error was generated.
     */
    public static synchronized void showErrorNotification(@NonNull Context context, @NonNull Error error,
                                                          @NonNull LocalSocketRunConfig localSocketRunConfig,
                                                          @Nullable LocalClientSocket clientSocket) {
        FablePluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
            localSocketRunConfig.getTitle() + " Socket Server Error", error.getMinimalErrorString(),
            LocalSocketManager.getErrorMarkdownString(error, localSocketRunConfig, clientSocket));
    }



    public static Boolean getFableAppAMSocketServerEnabled(@NonNull Context currentPackageContext) {
        boolean isFableApp = TermuxConstants.TERMUX_PACKAGE_NAME.equals(currentPackageContext.getPackageName());
        if (isFableApp) {
            return TERMUX_APP_AM_SOCKET_SERVER_ENABLED;
        } else {
            // Currently, unsupported since plugin app processes don't know that value is set in termux
            // app process FableAmSocketServer class. A binder API or a way to check if server is actually
            // running needs to be used. Long checks would also not be possible on main application thread
            return null;
        }

    }





    /** Enhanced implementation for {@link AmSocketServer.AmSocketServerClient} for {@link FableAmSocketServer}. */
    public static class FableAmSocketServerClient extends AmSocketServer.AmSocketServerClient {

        public static final String LOG_TAG = "FableAmSocketServerClient";

        @Nullable
        @Override
        public Thread.UncaughtExceptionHandler getLocalSocketManagerClientThreadUEH(
            @NonNull LocalSocketManager localSocketManager) {
            // Use termux crash handler for socket listener thread just like used for main app process thread.
            return FableCrashUtils.getCrashHandler(localSocketManager.getContext());
        }

        @Override
        public void onError(@NonNull LocalSocketManager localSocketManager,
                            @Nullable LocalClientSocket clientSocket, @NonNull Error error) {
            // Don't show notification if server is not running since errors may be triggered
            // when server is stopped and server and client sockets are closed.
            if (localSocketManager.isRunning()) {
                FableAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                    localSocketManager.getLocalSocketRunConfig(), clientSocket);
            }

            // But log the exception
            super.onError(localSocketManager, clientSocket, error);
        }

        @Override
        public void onDisallowedClientConnected(@NonNull LocalSocketManager localSocketManager,
                                                @NonNull LocalClientSocket clientSocket, @NonNull Error error) {
            // Always show notification and log error regardless of if server is running or not
            FableAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                localSocketManager.getLocalSocketRunConfig(), clientSocket);
            super.onDisallowedClientConnected(localSocketManager, clientSocket, error);
        }



        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

    }

}
