package com.gph.fable.shared.settings.properties

import android.content.Context
import androidx.annotation.NonNull
import java.util.Properties

/**
 * An interface that must be defined by the caller of the [SharedProperties] class.
 */
interface SharedPropertiesParser {

    /**
     * Called when properties are loaded from file to allow client to update the [Properties]
     * loaded from properties file before key/value pairs are stored in the in-memory cache.
     */
    @NonNull
    fun preProcessPropertiesOnReadFromDisk(@NonNull context: Context, @NonNull properties: Properties): Properties

    /**
     * A function that should return the internal [Any] to be stored for a key/value pair
     * read from properties file in the in-memory cache.
     */
    fun getInternalPropertyValueFromValue(@NonNull context: Context, key: String?, value: String?): Any?
}
