package com.gph.fable.shared.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.NonNull
import androidx.annotation.Nullable

/** A class that holds [SharedPreferences] objects for apps. */
open class AppSharedPreferences @JvmOverloads protected constructor(
    @NonNull context: Context,
    @Nullable sharedPreferences: SharedPreferences?,
    @Nullable multiProcessSharedPreferences: SharedPreferences? = null
) {

    /** The [Context] for operations. */
    @JvmField
    protected val mContext: Context

    /** The [SharedPreferences] that ideally should be created with [SharedPreferenceUtils.getPrivateSharedPreferences]. */
    @JvmField
    protected val mSharedPreferences: SharedPreferences?

    /** The [SharedPreferences] that ideally should be created with [SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences]. */
    @JvmField
    protected val mMultiProcessSharedPreferences: SharedPreferences?

    init {
        mContext = context
        mSharedPreferences = sharedPreferences
        mMultiProcessSharedPreferences = multiProcessSharedPreferences
    }

    /** Get [mContext]. */
    fun getContext(): Context {
        return mContext
    }

    /** Get [mSharedPreferences]. */
    fun getSharedPreferences(): SharedPreferences? {
        return mSharedPreferences
    }

    /** Get [mMultiProcessSharedPreferences]. */
    fun getMultiProcessSharedPreferences(): SharedPreferences? {
        return mMultiProcessSharedPreferences
    }
}
