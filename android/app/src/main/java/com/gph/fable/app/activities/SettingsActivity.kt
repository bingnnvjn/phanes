package com.gph.fable.app.activities

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gph.fable.R
import com.gph.fable.app.fragments.settings.FablePreferenceFragment
import com.gph.fable.app.models.UserAction
import com.gph.fable.shared.activities.ReportActivity
import com.gph.fable.shared.activity.media.AppCompatActivityUtils
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.models.ReportInfo
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.theme.FableThemeUtils
import com.gph.fable.shared.view.SystemBarInsets

open class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val themeMode = FableThemeUtils.getThemeMode(this, null)
        FableThemeUtils.setAppNightMode(themeMode)
        AppCompatActivityUtils.setNightMode(this, themeMode, true)

        setContentView(R.layout.activity_settings)
        SystemBarInsets.applyTopSystemBarInsets(findViewById(android.R.id.content))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, RootPreferencesFragment())
                .commit()
        }

        AppCompatActivityUtils.setToolbar(this, com.gph.fable.shared.R.id.toolbar)
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment!!
        )
        fragment.arguments = pref.extras

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    @Suppress("DEPRECATION")
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * XML/Manifest 外部名称依赖二进制名
     * {@code SettingsActivity$RootPreferencesFragment}；不要改为 inner 或顶层类。
     */
    @Keep
    open class RootPreferencesFragment : FablePreferenceFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = context ?: return
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            Thread { configureAboutPreference(context) }.start()
        }

        private fun configureAboutPreference(context: Context) {
            val aboutPreference = findPreference<Preference>("about") ?: return
            aboutPreference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    Thread {
                        val title = context.getString(R.string.about_preference_title)
                        val aboutString = buildString {
                            append(context.getString(R.string.about_fable_header)).append("\n\n")
                            append(context.getString(R.string.about_fable_description)).append("\n\n")
                            append(
                                FableUtils.getAppInfoMarkdownString(
                                    context,
                                    FableUtils.AppInfoMode.TERMUX_PACKAGE
                                )
                            )
                            append("\n\n").append(
                                AndroidUtils.getDeviceInfoMarkdownString(context, true)
                            )
                        }

                        val userActionName = UserAction.ABOUT.getName()
                        val reportInfo = ReportInfo(
                            userActionName,
                            TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME,
                            title
                        )
                        reportInfo.setReportString(aboutString)
                        reportInfo.setReportSaveFileLabelAndPath(
                            userActionName,
                            Environment.getExternalStorageDirectory().toString() + "/" +
                                FileUtils.sanitizeFileName(
                                    TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log",
                                    true,
                                    true
                                )
                        )
                        ReportActivity.startReportActivity(context, reportInfo)
                    }.start()
                    true
                }
        }
    }
}
