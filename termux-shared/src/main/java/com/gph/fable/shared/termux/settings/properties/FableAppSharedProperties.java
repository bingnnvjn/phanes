package com.gph.fable.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import com.gph.fable.shared.termux.TermuxConstants;

public class FableAppSharedProperties extends FableSharedProperties {

    private static FableAppSharedProperties properties;


    private FableAppSharedProperties(@NonNull Context context) {
        super(context, TermuxConstants.TERMUX_APP_NAME,
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST,
            new FableSharedProperties.SharedPropertiesParserClient());
    }

    /**
     * Initialize the {@link #properties} and load properties from disk.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link FableAppSharedProperties}.
     */
    public static FableAppSharedProperties init(@NonNull Context context) {
        if (properties == null)
            properties = new FableAppSharedProperties(context);

        return properties;
    }

    /**
     * Get the {@link #properties}.
     *
     * @return Returns the {@link FableAppSharedProperties}.
     */
    public static FableAppSharedProperties getProperties() {
        return properties;
    }

}
