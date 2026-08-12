package com.gph.fable.shared.termux.settings.preferences;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.android.PackageUtils;
import com.gph.fable.shared.settings.preferences.AppSharedPreferences;
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils;
import com.gph.fable.shared.termux.FableUtils;
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_BOOT_APP;
import com.gph.fable.shared.termux.TermuxConstants;

public class FableBootAppSharedPreferences extends AppSharedPreferences {

    private static final String LOG_TAG = "FableBootAppSharedPreferences";

    private FableBootAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));
    }

    /**
     * Get {@link FableBootAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_BOOT_PACKAGE_NAME}.
     * @return Returns the {@link FableBootAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static FableBootAppSharedPreferences build(@NonNull final Context context) {
        Context fableBootPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME);
        if (fableBootPackageContext == null)
            return null;
        else
            return new FableBootAppSharedPreferences(fableBootPackageContext);
    }

    /**
     * Get {@link FableBootAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_BOOT_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link FableBootAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static FableBootAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context fableBootPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME, exitAppOnError);
        if (fableBootPackageContext == null)
            return null;
        else
            return new FableBootAppSharedPreferences(fableBootPackageContext);
    }



    public int getLogLevel(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
        else
            return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel, boolean commitToFile) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, logLevel, commitToFile);
    }

}
