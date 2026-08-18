package com.gph.fable.app.fragments.settings.termux;

import android.content.Context;

import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/** 调试、键盘和终端视图 PreferenceDataStore 的公开偏好语义。 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TermuxPreferencesDataStoreTest {

    private Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void debugging_switches_round_trip_and_unknown_key_returns_false() {
        DebuggingPreferencesDataStore store = DebuggingPreferencesDataStore.getInstance(context());

        store.putBoolean("terminal_view_key_logging_enabled", true);
        store.putBoolean("crash_report_notifications_enabled", true);

        Assert.assertTrue(store.getBoolean("terminal_view_key_logging_enabled", false));
        Assert.assertTrue(store.getBoolean("crash_report_notifications_enabled", false));
        Assert.assertFalse(store.getBoolean("unknown", true));
    }

    @Test
    public void keyboard_switches_round_trip_and_unknown_key_returns_false() {
        TerminalIOPreferencesDataStore store = TerminalIOPreferencesDataStore.getInstance(context());

        store.putBoolean("soft_keyboard_enabled", false);
        store.putBoolean("soft_keyboard_enabled_only_if_no_hardware", true);

        Assert.assertFalse(store.getBoolean("soft_keyboard_enabled", true));
        Assert.assertTrue(store.getBoolean("soft_keyboard_enabled_only_if_no_hardware", false));
        Assert.assertFalse(store.getBoolean("unknown", true));
    }

    @Test
    public void terminal_view_font_size_and_margin_round_trip() {
        TerminalViewPreferencesDataStore store = TerminalViewPreferencesDataStore.getInstance(context());

        store.putInt(TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE, 16);
        store.putBoolean("terminal_margin_adjustment", true);

        Assert.assertEquals(16, store.getInt(
            TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE, 12));
        Assert.assertTrue(store.getBoolean("terminal_margin_adjustment", false));
        Assert.assertEquals(19, store.getInt("unknown", 19));
        Assert.assertFalse(store.getBoolean("unknown", true));
    }
}
