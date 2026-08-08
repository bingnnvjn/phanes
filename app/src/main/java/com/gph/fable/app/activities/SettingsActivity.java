package com.gph.fable.app.activities;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.gph.fable.R;
import com.gph.fable.shared.activities.ReportActivity;
import com.gph.fable.shared.file.FileUtils;
import com.gph.fable.shared.models.ReportInfo;
import com.gph.fable.app.models.UserAction;
import com.gph.fable.shared.android.AndroidUtils;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.TermuxUtils;
import com.gph.fable.shared.activity.media.AppCompatActivityUtils;
import com.gph.fable.shared.termux.theme.TermuxThemeUtils;

import android.os.Environment;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String themeMode = TermuxThemeUtils.getThemeMode(this, null);
        TermuxThemeUtils.setAppNightMode(themeMode);
        AppCompatActivityUtils.setNightMode(this, themeMode, true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.gph.fable.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureAboutPreference(context);
                }
            }.start();
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = context.getString(R.string.about_preference_title);

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(context.getString(R.string.about_fable_header)).append("\n\n");
                            aboutString.append(context.getString(R.string.about_fable_description)).append("\n\n");
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_PACKAGE));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }
    }

}
