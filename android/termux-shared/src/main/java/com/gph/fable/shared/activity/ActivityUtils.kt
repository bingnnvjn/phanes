package com.gph.fable.shared.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.errors.FunctionErrno

object ActivityUtils {

    private const val LOG_TAG = "ActivityUtils"

    /**
     * Wrapper for [startActivity].
     */
    @JvmStatic
    fun startActivity(@NonNull context: Context, @NonNull intent: Intent): Error? {
        return startActivity(context, intent, true, true)
    }

    /**
     * Start an [Activity].
     */
    @JvmStatic
    fun startActivity(context: Context?, @NonNull intent: Intent,
                      logErrorMessage: Boolean, showErrorMessage: Boolean): Error? {
        val error: Error?
        val activityName = intent.component?.className ?: "Unknown"

        if (context == null) {
            error = ActivityErrno.ERRNO_STARTING_ACTIVITY_WITH_NULL_CONTEXT.getError(activityName)
            if (logErrorMessage)
                error.logErrorAndShowToast(null, LOG_TAG)
            return error
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val error = ActivityErrno.ERRNO_START_ACTIVITY_FAILED_WITH_EXCEPTION.getError(e, activityName, e.message)
            if (logErrorMessage)
                error.logErrorAndShowToast(if (showErrorMessage) context else null, LOG_TAG)
            return error
        }

        return null
    }

    /**
     * Wrapper for [startActivityForResult].
     */
    @JvmStatic
    fun startActivityForResult(context: Context?, requestCode: Int, @NonNull intent: Intent): Error? {
        return startActivityForResult(context, requestCode, intent, true, true, null)
    }

    /**
     * Wrapper for [startActivityForResult].
     */
    @JvmStatic
    fun startActivityForResult(context: Context?, requestCode: Int, @NonNull intent: Intent,
                               logErrorMessage: Boolean, showErrorMessage: Boolean): Error? {
        return startActivityForResult(context, requestCode, intent, logErrorMessage, showErrorMessage, null)
    }

    /**
     * Start an [Activity] for result.
     */
    @JvmStatic
    fun startActivityForResult(context: Context?, requestCode: Int, @NonNull intent: Intent,
                               logErrorMessage: Boolean, showErrorMessage: Boolean,
                               @Nullable activityResultLauncher: ActivityResultLauncher<Intent>?): Error? {
        val error: Error?
        val activityName = intent.component?.className ?: "Unknown"
        try {
            if (activityResultLauncher != null) {
                activityResultLauncher.launch(intent)
            } else {
                if (context == null) {
                    error = ActivityErrno.ERRNO_STARTING_ACTIVITY_WITH_NULL_CONTEXT.getError(activityName)
                    if (logErrorMessage)
                        error.logErrorAndShowToast(null, LOG_TAG)
                    return error
                }

                if (context is AppCompatActivity)
                    context.startActivityForResult(intent, requestCode)
                else if (context is Activity)
                    context.startActivityForResult(intent, requestCode)
                else {
                    val error = FunctionErrno.ERRNO_PARAMETER_NOT_INSTANCE_OF.getError("context", "startActivityForResult", "Activity or AppCompatActivity")
                    if (logErrorMessage)
                        error.logErrorAndShowToast(if (showErrorMessage) context else null, LOG_TAG)
                    return error
                }
            }
        } catch (e: Exception) {
            val error = ActivityErrno.ERRNO_START_ACTIVITY_FOR_RESULT_FAILED_WITH_EXCEPTION.getError(e, activityName, e.message)
            if (logErrorMessage)
                error.logErrorAndShowToast(if (showErrorMessage) context else null, LOG_TAG)
            return error
        }

        return null
    }
}
