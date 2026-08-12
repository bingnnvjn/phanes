package com.gph.fable.shared.termux.settings.preferences;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.android.PackageUtils;
import com.gph.fable.shared.settings.preferences.AppSharedPreferences;
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils;
import com.gph.fable.shared.termux.FableUtils;
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_API_APP;
import com.gph.fable.shared.termux.TermuxConstants;

public class FableAPIAppSharedPreferences extends AppSharedPreferences {

    private static final String LOG_TAG = "FableAPIAppSharedPreferences";

    private FableAPIAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));
    }

    /**
     * Get {@link FableAPIAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_API_PACKAGE_NAME}.
     * @return Returns the {@link FableAPIAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static FableAPIAppSharedPreferences build(@NonNull final Context context) {
        Context fableAPIPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_API_PACKAGE_NAME);
        if (fableAPIPackageContext == null)
            return null;
        else
            return new FableAPIAppSharedPreferences(fableAPIPackageContext);
    }

    /**
     * Get {@link FableAPIAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_API_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link FableAPIAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static FableAPIAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context fableAPIPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_API_PACKAGE_NAME, exitAppOnError);
        if (fableAPIPackageContext == null)
            return null;
        else
            return new FableAPIAppSharedPreferences(fableAPIPackageContext);
    }



    public int getLogLevel(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
        else
            return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel, boolean commitToFile) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, logLevel, commitToFile);
    }


    public int getLastPendingIntentRequestCode() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE);
    }

    public void setLastPendingIntentRequestCode(int lastPendingIntentRequestCode) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, lastPendingIntentRequestCode, true);
    }

}
