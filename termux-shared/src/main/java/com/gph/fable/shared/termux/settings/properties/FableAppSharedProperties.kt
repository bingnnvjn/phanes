package com.gph.fable.shared.termux.settings.properties

import android.content.Context
import androidx.annotation.NonNull
import com.gph.fable.shared.termux.TermuxConstants

class FableAppSharedProperties private constructor(@NonNull context: Context) :
    FableSharedProperties(
        context, TermuxConstants.TERMUX_APP_NAME,
        TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST,
        FableSharedProperties.SharedPropertiesParserClient()
    ) {

    companion object {
        private var properties: FableAppSharedProperties? = null

        /**
         * Initialize the [properties] and load properties from disk.
         */
        @JvmStatic
        fun init(@NonNull context: Context): FableAppSharedProperties {
            if (properties == null)
                properties = FableAppSharedProperties(context)

            return properties!!
        }

        /**
         * Get the [properties].
         */
        @JvmStatic
        fun getProperties(): FableAppSharedProperties? {
            return properties
        }
    }
}
