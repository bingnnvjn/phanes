package com.gph.fable.shared.android

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.reflection.ReflectionUtils
import java.lang.reflect.Method

/**
 * Utils for Developer Options -> Feature Flags.
 */
object FeatureFlagUtils {

    enum class FeatureFlagValue(private val mName: String) {

        /** Unknown like due to exception raised while getting value. */
        UNKNOWN("<unknown>"),

        /** Flag is unsupported on current android build. */
        UNSUPPORTED("<unsupported>"),

        /** Flag is enabled. */
        TRUE("true"),

        /** Flag is not enabled. */
        FALSE("false");

        fun getName(): String {
            return mName
        }
    }

    const val FEATURE_FLAGS_CLASS = "android.util.FeatureFlagUtils"

    private const val LOG_TAG = "FeatureFlagUtils"

    /**
     * Get all feature flags in their raw form.
     */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun getAllFeatureFlags(): Map<String, String>? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            @SuppressLint("PrivateApi")
            val clazz = Class.forName(FEATURE_FLAGS_CLASS)
            val getAllFeatureFlagsMethod = ReflectionUtils.getDeclaredMethod(clazz, "getAllFeatureFlags")
            if (getAllFeatureFlagsMethod == null) return null
            ReflectionUtils.invokeMethod(getAllFeatureFlagsMethod, null).value as Map<String, String>?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get all feature flags", e)
            null
        }
    }

    /**
     * Check if a feature flag exists.
     */
    @Nullable
    @JvmStatic
    fun featureFlagExists(@NonNull feature: String): Boolean? {
        val featureFlags = getAllFeatureFlags()
        if (featureFlags == null) return null
        return featureFlags.containsKey(feature)
    }

    /**
     * Get [FeatureFlagValue] for a feature.
     */
    @NonNull
    @JvmStatic
    fun getFeatureFlagValueString(@NonNull context: Context, @NonNull feature: String): FeatureFlagValue {
        val featureFlagExists = featureFlagExists(feature)
        if (featureFlagExists == null) {
            Logger.logError(LOG_TAG, "Failed to get feature flags \"" + feature + "\" value")
            return FeatureFlagValue.UNKNOWN
        } else if (!featureFlagExists) {
            return FeatureFlagValue.UNSUPPORTED
        }

        val featureFlagValue = isFeatureEnabled(context, feature)
        return if (featureFlagValue == null) {
            Logger.logError(LOG_TAG, "Failed to get feature flags \"" + feature + "\" value")
            FeatureFlagValue.UNKNOWN
        } else {
            if (featureFlagValue) FeatureFlagValue.TRUE else FeatureFlagValue.FALSE
        }
    }

    /**
     * Check if a feature flag exists.
     */
    @Nullable
    @JvmStatic
    fun isFeatureEnabled(@NonNull context: Context, @NonNull feature: String): Boolean? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            @SuppressLint("PrivateApi")
            val clazz = Class.forName(FEATURE_FLAGS_CLASS)
            val isFeatureEnabledMethod = ReflectionUtils.getDeclaredMethod(clazz, "isEnabled", Context::class.java, String::class.java)
            if (isFeatureEnabledMethod == null) {
                Logger.logError(LOG_TAG, "Failed to check if feature flag \"" + feature + "\" is enabled")
                return null
            }

            ReflectionUtils.invokeMethod(isFeatureEnabledMethod, null, context, feature).value as Boolean?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check if feature flag \"" + feature + "\" is enabled", e)
            null
        }
    }
}
