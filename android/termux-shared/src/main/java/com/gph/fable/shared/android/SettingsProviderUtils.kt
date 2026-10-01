package com.gph.fable.shared.android

import android.content.Context
import android.provider.Settings
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.logger.Logger

object SettingsProviderUtils {

    private const val LOG_TAG = "SettingsProviderUtils"

    /** The namespaces for [Settings] provider. */
    enum class SettingNamespace {
        /** The [Settings.Global] namespace */
        GLOBAL,

        /** The [Settings.Secure] namespace */
        SECURE,

        /** The [Settings.System] namespace */
        SYSTEM
    }

    /** The type of values for [Settings] provider. */
    enum class SettingType {
        FLOAT,
        INT,
        LONG,
        STRING,
        URI
    }

    /**
     * Get settings key value from [SettingNamespace] namespace and of [SettingType] type.
     */
    @Nullable
    @JvmStatic
    fun getSettingsValue(@NonNull context: Context, @NonNull namespace: SettingNamespace,
                         @NonNull type: SettingType, @NonNull key: String, @Nullable def: Any?): Any? {
        return try {
            when (namespace) {
                SettingNamespace.GLOBAL -> when (type) {
                    SettingType.FLOAT -> Settings.Global.getFloat(context.contentResolver, key)
                    SettingType.INT -> Settings.Global.getInt(context.contentResolver, key)
                    SettingType.LONG -> Settings.Global.getLong(context.contentResolver, key)
                    SettingType.STRING -> Settings.Global.getString(context.contentResolver, key)
                    SettingType.URI -> Settings.Global.getUriFor(key)
                }
                SettingNamespace.SECURE -> when (type) {
                    SettingType.FLOAT -> Settings.Secure.getFloat(context.contentResolver, key)
                    SettingType.INT -> Settings.Secure.getInt(context.contentResolver, key)
                    SettingType.LONG -> Settings.Secure.getLong(context.contentResolver, key)
                    SettingType.STRING -> Settings.Secure.getString(context.contentResolver, key)
                    SettingType.URI -> Settings.Secure.getUriFor(key)
                }
                SettingNamespace.SYSTEM -> when (type) {
                    SettingType.FLOAT -> Settings.System.getFloat(context.contentResolver, key)
                    SettingType.INT -> Settings.System.getInt(context.contentResolver, key)
                    SettingType.LONG -> Settings.System.getLong(context.contentResolver, key)
                    SettingType.STRING -> Settings.System.getString(context.contentResolver, key)
                    SettingType.URI -> Settings.System.getUriFor(key)
                }
            }
        } catch (e: Settings.SettingNotFoundException) {
            // Ignore
            def
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "Failed to get \"" + key + "\" key value from settings \"" + namespace.name + "\" namespace of type \"" + type.name + "\"")
            def
        }
    }
}
