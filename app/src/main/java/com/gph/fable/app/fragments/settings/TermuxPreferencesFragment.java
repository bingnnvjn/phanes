package com.gph.fable.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;

import com.gph.fable.R;
import com.gph.fable.shared.activity.media.AppCompatActivityUtils;
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.gph.fable.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.gph.fable.shared.termux.theme.TermuxThemeUtils;

@Keep
public class TermuxPreferencesFragment extends FablePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TermuxPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);

        // 主题三选一：选择后立即应用并重建设置页，返回终端时同样生效。
        ListPreference themeModePreference = findPreference(
            TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE);
        if (themeModePreference != null && getActivity() instanceof AppCompatActivity) {
            themeModePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String mode = String.valueOf(newValue);
                TermuxThemeUtils.setAppNightMode(mode);
                AppCompatActivityUtils.setNightMode((AppCompatActivity) getActivity(), mode, true);
                getActivity().recreate();
                return true;
            });
        }
    }

}

class TermuxPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TermuxPreferencesDataStore mInstance;

    private TermuxPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TermuxPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TermuxPreferencesDataStore(context);
        }
        return mInstance;
    }

    @Override
    @Nullable
    public String getString(String key, @Nullable String defValue) {
        if (mPreferences == null) return defValue;
        if (key == null) return defValue;

        switch (key) {
            case TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE:
                String mode = mPreferences.getThemeMode();
                return mode != null ? mode
                    : TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_FABLE_THEME_MODE;
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, @Nullable String value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE:
                mPreferences.setThemeMode(value);
                break;
            default:
                break;
        }
    }

}
