package com.gph.fable.app.fragments.settings;

import android.content.Context;

import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/** 设置主题的 PreferenceDataStore 对外读写契约。 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FablePreferencesDataStoreTest {

    @Test
    public void theme_mode_round_trips_and_unmanaged_key_uses_caller_default() {
        Context context = RuntimeEnvironment.getApplication();
        FablePreferencesDataStore store = FablePreferencesDataStore.getInstance(context);

        store.putString(TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE, "dark");

        Assert.assertEquals("dark", store.getString(
            TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE, "system"));
        Assert.assertEquals("caller-default", store.getString("unknown", "caller-default"));
    }
}
