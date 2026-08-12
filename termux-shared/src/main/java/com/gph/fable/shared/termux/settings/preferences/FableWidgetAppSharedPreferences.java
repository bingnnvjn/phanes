package com.gph.fable.shared.termux.settings.preferences;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.android.PackageUtils;
import com.gph.fable.shared.settings.preferences.AppSharedPreferences;
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils;
import com.gph.fable.shared.termux.FableUtils;
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_WIDGET_APP;
import com.gph.fable.shared.termux.TermuxConstants;

import java.util.UUID;

public class FableWidgetAppSharedPreferences extends AppSharedPreferences {

    private static final String LOG_TAG = "FableWidgetAppSharedPreferences";

    private FableWidgetAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));
    }

    /**
     * Get {@link FableWidgetAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_WIDGET_PACKAGE_NAME}.
     * @return Returns the {@link FableWidgetAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static FableWidgetAppSharedPreferences build(@NonNull final Context context) {
        Context fableWidgetPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME);
        if (fableWidgetPackageContext == null)
            return null;
        else
            return new FableWidgetAppSharedPreferences(fableWidgetPackageContext);
    }

    /**
     * Get the {@link FableWidgetAppSharedPreferences}.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_WIDGET_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link FableWidgetAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static FableWidgetAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        Context fableWidgetPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME, exitAppOnError);
        if (fableWidgetPackageContext == null)
            return null;
        else
            return new FableWidgetAppSharedPreferences(fableWidgetPackageContext);
    }



    public static String getGeneratedToken(@NonNull Context context) {
        FableWidgetAppSharedPreferences preferences = FableWidgetAppSharedPreferences.build(context, true);
        if (preferences == null) return null;
        return preferences.getGeneratedToken();
    }

    public String getGeneratedToken() {
        String token =  SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_WIDGET_APP.KEY_TOKEN, null, true);
        if (token == null) {
            token = UUID.randomUUID().toString();
            SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_WIDGET_APP.KEY_TOKEN, token, true);
        }
        return token;
    }



    public int getLogLevel(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
        else
            return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel, boolean commitToFile) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, logLevel, commitToFile);
    }

}
