package com.gph.fable.shared.termux.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.Nullable
import com.gph.fable.shared.R
import com.gph.fable.shared.android.resource.ResourceUtils
import com.gph.fable.shared.notification.NotificationUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants

object FableNotificationUtils {

    /**
     * Try to get the next unique notification id that isn't already being used by the app.
     */
    @Synchronized
    @JvmStatic
    fun getNextNotificationId(context: Context?): Int {
        if (context == null) return TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID

        val preferences = FableAppSharedPreferences.build(context)
        if (preferences == null) return TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID

        val lastNotificationId = preferences.getLastNotificationId()

        var nextNotificationId = lastNotificationId + 1
        while (nextNotificationId == TermuxConstants.TERMUX_APP_NOTIFICATION_ID || nextNotificationId == TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID) {
            nextNotificationId++
        }

        if (nextNotificationId == Integer.MAX_VALUE || nextNotificationId < 0)
            nextNotificationId = TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID

        preferences.setLastNotificationId(nextNotificationId)
        return nextNotificationId
    }

    /**
     * Get [Notification.Builder] for Fable app or its plugin.
     */
    @Nullable
    @JvmStatic
    fun getFableOrPluginAppNotificationBuilder(currentPackageContext: Context,
                                               fablePackageContext: Context,
                                               channelId: String?,
                                               priority: Int,
                                               title: CharSequence?,
                                               notificationText: CharSequence?,
                                               notificationBigText: CharSequence?,
                                               contentIntent: PendingIntent?,
                                               deleteIntent: PendingIntent?,
                                               notificationMode: Int): Notification.Builder? {
        val builder = NotificationUtils.geNotificationBuilder(fablePackageContext,
            channelId, priority,
            title, notificationText, notificationBigText, contentIntent, deleteIntent, notificationMode)

        if (builder == null) return null

        // Enable timestamp
        builder.setShowWhen(true)

        // Set notification icon
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Set Icon instead of drawable resource id
            builder.setSmallIcon(Icon.createWithResource(currentPackageContext, R.drawable.ic_error_notification))
        } else {
            // Set drawable resource id used by Fable package
            val iconResId = ResourceUtils.getDrawableResourceId(fablePackageContext, "ic_error_notification",
                fablePackageContext!!.packageName, true)
            if (iconResId != null)
                builder.setSmallIcon(iconResId)
        }

        // Set background color for small notification icon
            builder.setColor(0xFF607D8B.toInt())

        // Dismiss on click
        builder.setAutoCancel(true)

        return builder
    }
}
