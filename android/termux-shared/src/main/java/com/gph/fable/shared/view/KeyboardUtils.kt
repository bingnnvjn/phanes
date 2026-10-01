package com.gph.fable.shared.view

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsCompat
import com.gph.fable.shared.logger.Logger

object KeyboardUtils {

    private const val LOG_TAG = "KeyboardUtils"

    @JvmStatic
    fun setSoftKeyboardVisibility(@NonNull showSoftKeyboardRunnable: Runnable, activity: Activity?, view: View?, visible: Boolean) {
        if (visible) {
            // A Runnable with a delay is used, otherwise soft keyboard may not automatically open
            // on some devices, but still may fail
            view!!.postDelayed(showSoftKeyboardRunnable, 500)
        } else {
            view?.removeCallbacks(showSoftKeyboardRunnable)
            hideSoftKeyboard(activity, view)
        }
    }

    /**
     * Toggle the soft keyboard.
     */
    @JvmStatic
    fun toggleSoftKeyboard(context: Context?) {
        if (context == null) return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (inputMethodManager != null)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    /**
     * Show the soft keyboard.
     */
    @JvmStatic
    fun showSoftKeyboard(context: Context?, view: View?) {
        if (context == null || view == null) return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (inputMethodManager != null)
            inputMethodManager.showSoftInput(view, 0)
    }

    @JvmStatic
    fun hideSoftKeyboard(context: Context?, view: View?) {
        if (context == null || view == null) return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (inputMethodManager != null)
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    @JvmStatic
    fun disableSoftKeyboard(activity: Activity?, view: View?) {
        if (activity == null || view == null) return
        hideSoftKeyboard(activity, view)
        setDisableSoftKeyboardFlags(activity)
    }

    @JvmStatic
    fun setDisableSoftKeyboardFlags(activity: Activity?) {
        if (activity != null && activity.window != null)
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    @JvmStatic
    fun clearDisableSoftKeyboardFlags(activity: Activity?) {
        if (activity != null && activity.window != null)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    @JvmStatic
    fun areDisableSoftKeyboardFlagsSet(activity: Activity?): Boolean {
        if (activity == null || activity.window == null) return false
        return (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
    }

    @JvmStatic
    fun setSoftKeyboardAlwaysHiddenFlags(activity: Activity?) {
        if (activity != null && activity.window != null)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    @JvmStatic
    fun setSoftInputModeAdjustResize(activity: Activity?) {
        if (activity != null && activity.window != null)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /**
     * Check if soft keyboard is visible.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @JvmStatic
    fun isSoftKeyboardVisible(activity: Activity?): Boolean {
        if (activity != null && activity.window != null) {
            val insets = activity.window.decorView.rootWindowInsets
            if (insets != null) {
                val insetsCompat = WindowInsetsCompat.toWindowInsetsCompat(insets)
                if (insetsCompat.isVisible(WindowInsetsCompat.Type.ime())) {
                    Logger.logVerbose(LOG_TAG, "Soft keyboard visible")
                    return true
                }
            }
        }

        Logger.logVerbose(LOG_TAG, "Soft keyboard not visible")
        return false
    }

    /**
     * Check if hardware keyboard is connected.
     */
    @JvmStatic
    fun isHardKeyboardConnected(context: Context?): Boolean {
        if (context == null) return false

        val config = context.resources.configuration
        return config.keyboard != Configuration.KEYBOARD_NOKEYS
            || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /**
     * Check if soft keyboard should be disabled based on user configuration.
     */
    @JvmStatic
    fun shouldSoftKeyboardBeDisabled(context: Context?, isSoftKeyboardEnabled: Boolean, isSoftKeyboardEnabledOnlyIfNoHardware: Boolean): Boolean {
        // If soft keyboard is disabled by user regardless of hardware keyboard
        if (!isSoftKeyboardEnabled) {
            return true
        } else {
            // If soft keyboard is disabled by user only if hardware keyboard is connected
            if (isSoftKeyboardEnabledOnlyIfNoHardware) {
                val isHardKeyboardConnected = KeyboardUtils.isHardKeyboardConnected(context)
                Logger.logVerbose(LOG_TAG, "Hardware keyboard connected=" + isHardKeyboardConnected)
                return isHardKeyboardConnected
            } else {
                return false
            }
        }
    }
}
