package com.gph.fable.app.terminal.io

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.app.terminal.FableTerminalSessionActivityClient
import com.gph.fable.app.terminal.FableTerminalViewClient
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.extrakeys.ExtraKeysConstants
import com.gph.fable.shared.termux.extrakeys.ExtraKeysInfo
import com.gph.fable.shared.termux.settings.properties.FableSharedProperties
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants
import com.gph.fable.shared.termux.terminal.io.TerminalExtraKeys
import com.gph.fable.view.TerminalView
import org.json.JSONException

open class FableTerminalExtraKeys(
    @JvmField val mActivity: FableActivity,
    terminalView: TerminalView,
    @JvmField val mFableTerminalViewClient: FableTerminalViewClient?,
    @JvmField val mFableTerminalSessionActivityClient: FableTerminalSessionActivityClient?
) : TerminalExtraKeys(terminalView) {

    private var mExtraKeysInfo: ExtraKeysInfo? = null

    init {
        setExtraKeys()
    }

    /**
     * Set the terminal extra keys and style.
     */
    private fun setExtraKeys() {
        mExtraKeysInfo = null

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            var extrakeys = mActivity.getProperties()
                .getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true) as String
            var extraKeysStyle = mActivity.getProperties()
                .getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true) as String

            val extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle)
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY == extraKeyDisplayMap &&
                TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE != extraKeysStyle
            ) {
                Logger.logError(
                    FableSharedProperties.LOG_TAG,
                    "The style \"$extraKeysStyle\" for the key \"" +
                        TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE +
                        "\" is invalid. Using default style instead."
                )
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE
            }

            mExtraKeysInfo = ExtraKeysInfo(
                extrakeys,
                extraKeysStyle,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES
            )
        } catch (e: JSONException) {
            Logger.showToast(
                mActivity,
                mActivity.getString(
                    R.string.error_load_extra_keys_property,
                    TermuxPropertyConstants.KEY_EXTRA_KEYS,
                    e.toString()
                ),
                true
            )
            Logger.logStackTraceWithMessage(
                LOG_TAG,
                "Could not load and set the \"" +
                    TermuxPropertyConstants.KEY_EXTRA_KEYS +
                    "\" property from the properties file: ",
                e
            )

            try {
                mExtraKeysInfo = ExtraKeysInfo(
                    TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
                    TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES
                )
            } catch (e2: JSONException) {
                Logger.showToast(
                    mActivity,
                    mActivity.getString(R.string.error_create_default_extra_keys),
                    true
                )
                Logger.logStackTraceWithMessage(
                    LOG_TAG,
                    "Could create default extra keys: ",
                    e
                )
                mExtraKeysInfo = null
            }
        }
    }

    open fun getExtraKeysInfo(): ExtraKeysInfo? = mExtraKeysInfo

    @SuppressLint("RtlHardcoded")
    public open override fun onTerminalExtraKeyButtonClick(
        view: View?,
        key: String?,
        ctrlDown: Boolean,
        altDown: Boolean,
        shiftDown: Boolean,
        fnDown: Boolean
    ) {
        if ("KEYBOARD" == key) {
            mFableTerminalViewClient?.onToggleSoftKeyboardRequest()
        } else if ("DRAWER" == key) {
            val drawerLayout: DrawerLayout = mFableTerminalViewClient!!
                .getActivity().getDrawer()
            if (drawerLayout.isDrawerOpen(Gravity.LEFT)) {
                drawerLayout.closeDrawer(Gravity.LEFT)
            } else {
                drawerLayout.openDrawer(Gravity.LEFT)
            }
        } else if ("PASTE" == key) {
            mFableTerminalSessionActivityClient?.onPasteTextFromClipboard(null)
        } else if ("SCROLL" == key) {
            val terminalView = mFableTerminalViewClient!!
                .getActivity().getTerminalView()
            // 工单 30：自动滚动切换走 TerminalView（新路径视图本地状态，旧路径旧模拟器）。
            terminalView?.toggleAutoScrollDisabled()
        } else {
            super.onTerminalExtraKeyButtonClick(
                view,
                key,
                ctrlDown,
                altDown,
                shiftDown,
                fnDown
            )
        }
    }

    private companion object {
        private const val LOG_TAG = "FableTerminalExtraKeys"
    }
}
