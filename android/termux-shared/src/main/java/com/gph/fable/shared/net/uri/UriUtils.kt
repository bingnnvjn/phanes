package com.gph.fable.shared.net.uri

import android.net.Uri
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.file.FileUtils

object UriUtils {

    /**
     * Get the full file path from a [Uri] including the fragment.
     *
     * If the [Uri] was created from file path with [Uri.parse], like "am"
     * command "-d" option does, and the path contained a "#", then anything after it would become
     * the fragment and [Uri.getPath] will only return the path before it, which would be
     * invalid. The fragment must be manually appended to the path to get the full path.
     *
     * If the [Uri] was created with [Uri.Builder] and path was set
     * with [Uri.Builder.path], then "#" will automatically be encoded to "%23"
     * and separate fragment will not exist.
     *
     * @param uri The [Uri] to get file path from.
     * @return Returns the file path if found, otherwise `null`.
     */
    @Nullable
    @JvmStatic
    fun getUriFilePathWithFragment(uri: Uri?): String? {
        if (uri == null) return null
        val path = uri.path
        if (DataUtils.isNullOrEmpty(path)) return null
        val fragment = uri.fragment
        return path + (if (DataUtils.isNullOrEmpty(fragment)) "" else "#" + fragment)
    }

    /**
     * Get the file basename from a [Uri]. The file basename is anything after last forward
     * slash "/" in the path, or the path itself if its not found.
     *
     * @param uri The [Uri] to get basename from.
     * @param withFragment If the [Uri] fragment should be included in basename.
     * @return Returns the file basename if found, otherwise `null`.
     */
    @Nullable
    @JvmStatic
    fun getUriFileBasename(uri: Uri?, withFragment: Boolean): String? {
        if (uri == null) return null

        val path: String
        if (withFragment) {
            path = getUriFilePathWithFragment(uri) ?: return null
        } else {
            path = uri.path ?: return null
            if (DataUtils.isNullOrEmpty(path)) return null
        }

        return FileUtils.getFileBasename(path)
    }

    /**
     * Get [UriScheme.SCHEME_FILE] [Uri] for path.
     *
     * @param path The path for the [Uri].
     * @return Returns the [Uri].
     */
    @JvmStatic
    fun getFileUri(@NonNull path: String): Uri {
        return Uri.Builder().scheme(UriScheme.SCHEME_FILE).path(path).build()
    }

    /**
     * Get [UriScheme.SCHEME_FILE] [Uri] for path.
     *
     * @param authority The authority for the [Uri].
     * @param path The path for the [Uri].
     * @return Returns the [Uri].
     */
    @JvmStatic
    fun getFileUri(@NonNull authority: String, @NonNull path: String): Uri {
        return Uri.Builder().scheme(UriScheme.SCHEME_FILE).authority(authority).path(path).build()
    }

    /**
     * Get [UriScheme.SCHEME_CONTENT] [Uri] for path.
     *
     * @param path The path for the [Uri].
     * @return Returns the [Uri].
     */
    @JvmStatic
    fun getContentUri(@NonNull path: String): Uri {
        return Uri.Builder().scheme(UriScheme.SCHEME_CONTENT).path(path).build()
    }

    /**
     * Get [UriScheme.SCHEME_CONTENT] [Uri] for path.
     *
     * @param authority The authority for the [Uri].
     * @param path The path for the [Uri].
     * @return Returns the [Uri].
     */
    @JvmStatic
    fun getContentUri(@NonNull authority: String, @NonNull path: String): Uri {
        return Uri.Builder().scheme(UriScheme.SCHEME_CONTENT).authority(authority).path(path).build()
    }
}
