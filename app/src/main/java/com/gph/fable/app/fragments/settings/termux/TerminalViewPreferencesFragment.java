package com.gph.fable.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;

import com.gph.fable.R;
import com.gph.fable.app.fragments.settings.FablePreferenceFragment;
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences;

@Keep
public class TerminalViewPreferencesFragment extends FablePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TerminalViewPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.fable_terminal_view_preferences, rootKey);
    }

}

class TerminalViewPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final FableAppSharedPreferences mPreferences;

    private static TerminalViewPreferencesDataStore mInstance;

    private TerminalViewPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = FableAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalViewPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalViewPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "terminal_margin_adjustment":
                    mPreferences.setTerminalMarginAdjustment(value);
                break;
            default:
                break;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        if (mPreferences == null || mContext == null) return defValue;
        if (key == null) return defValue;

        switch (key) {
            case TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE:
                return mPreferences.getFontSizeDp(mContext);
            default:
                return defValue;
        }
    }

    @Override
    public void putInt(String key, int value) {
        if (mPreferences == null || mContext == null) return;
        if (key == null) return;

        switch (key) {
            case TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE:
                mPreferences.setFontSizeDp(mContext, value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "terminal_margin_adjustment":
                return mPreferences.isTerminalMarginAdjustmentEnabled();
            default:
                return false;
        }
    }

}
