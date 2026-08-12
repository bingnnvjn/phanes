package com.gph.fable.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.gph.fable.app.FableActivity;
import com.gph.fable.R;
import com.gph.fable.app.terminal.FableTerminalSessionActivityClient;
import com.gph.fable.app.terminal.FableTerminalViewClient;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.termux.extrakeys.ExtraKeysConstants;
import com.gph.fable.shared.termux.extrakeys.ExtraKeysInfo;
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants;
import com.gph.fable.shared.termux.settings.properties.FableSharedProperties;
import com.gph.fable.shared.termux.terminal.io.TerminalExtraKeys;
import com.gph.fable.view.TerminalView;

import org.json.JSONException;

public class FableTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;

    final FableActivity mActivity;
    final FableTerminalViewClient mFableTerminalViewClient;
    final FableTerminalSessionActivityClient mFableTerminalSessionActivityClient;

    private static final String LOG_TAG = "FableTerminalExtraKeys";

    public FableTerminalExtraKeys(FableActivity activity, @NonNull TerminalView terminalView,
                                   FableTerminalViewClient termuxTerminalViewClient,
                                   FableTerminalSessionActivityClient fableTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mFableTerminalViewClient = termuxTerminalViewClient;
        mFableTerminalSessionActivityClient = fableTerminalSessionActivityClient;

        setExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfo = null;

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(FableSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfo = new ExtraKeysInfo(extrakeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.showToast(mActivity, mActivity.getString(R.string.error_load_extra_keys_property,
                TermuxPropertyConstants.KEY_EXTRA_KEYS, e.toString()), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e2) {
                Logger.showToast(mActivity, mActivity.getString(R.string.error_create_default_extra_keys), true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
            }
        }
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mFableTerminalViewClient != null)
                mFableTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mFableTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("PASTE".equals(key)) {
            if(mFableTerminalSessionActivityClient != null)
                mFableTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mFableTerminalViewClient.getActivity().getTerminalView();
            // 工单 30：自动滚动切换走 TerminalView（新路径视图本地状态，旧路径旧模拟器）。
            if (terminalView != null)
                terminalView.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
