package com.gph.fable.app.activities;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.gph.fable.R;
import com.gph.fable.app.fragments.settings.FablePreferenceFragment;
import com.gph.fable.shared.activities.ReportActivity;
import com.gph.fable.shared.file.FileUtils;
import com.gph.fable.shared.models.ReportInfo;
import com.gph.fable.app.models.UserAction;
import com.gph.fable.shared.android.AndroidUtils;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.TermuxUtils;
import com.gph.fable.shared.activity.media.AppCompatActivityUtils;
import com.gph.fable.shared.termux.theme.TermuxThemeUtils;
import com.gph.fable.shared.view.SystemBarInsets;

import android.os.Environment;

public class SettingsActivity extends AppCompatActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String themeMode = TermuxThemeUtils.getThemeMode(this, null);
        TermuxThemeUtils.setAppNightMode(themeMode);
        AppCompatActivityUtils.setNightMode(this, themeMode, true);

        setContentView(R.layout.activity_settings);
        // 工单 05：edge-to-edge 顶部避让状态栏（底部由设置列表自行适配，见 fragment onViewCreated）。
        SystemBarInsets.applyTopSystemBarInsets(findViewById(android.R.id.content));
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.gph.fable.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    /**
     * 工单 05：设置子页面过渡动画。走系统提供的动画机制：
     * {@link FragmentTransaction#TRANSIT_FRAGMENT_OPEN}（系统默认过渡）
     * + {@code setReorderingAllowed(true)}（系统预测性返回动画）。
     * 不写自定义动画。
     */
    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.getFragmentFactory().instantiate(getClassLoader(), pref.getFragment());
        fragment.setArguments(pref.getExtras());

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.setReorderingAllowed(true);
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        transaction.replace(R.id.settings, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends FablePreferenceFragment {
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
