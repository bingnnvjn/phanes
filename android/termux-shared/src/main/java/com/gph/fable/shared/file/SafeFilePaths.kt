package com.gph.fable.shared.file

import java.io.File
import java.io.IOException

/**
 * Canonical filesystem containment for paths that cross an external-input seam.
 *
 * Callers supply the allowed root. This module resolves symlinks and path traversal,
 * and returns a canonical file only when it stays inside that root.
 */
object SafeFilePaths {

    /**
     * Returns true when [candidate] resolves to [root] or a descendant of [root].
     * Set [allowRoot] to false when only descendants are valid.
     */
    @JvmStatic
    fun isWithin(root: File?, candidate: File?, allowRoot: Boolean): Boolean {
        if (root == null || candidate == null) return false
        return try {
            val canonicalRoot = root.canonicalFile
            val canonicalCandidate = candidate.canonicalFile
            if (canonicalCandidate == canonicalRoot) {
                allowRoot
            } else {
                val rootPrefix = if (canonicalRoot.path.endsWith(File.separator)) {
                    canonicalRoot.path
                } else {
                    canonicalRoot.path + File.separator
                }
                canonicalCandidate.path.startsWith(rootPrefix)
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Resolves one external file name under [root].
     *
     * The name must be a single leaf. Absolute paths, separators, dot segments,
     * blank names, and existing symlinks that escape [root] are rejected.
     */
    @JvmStatic
    fun resolveLeaf(root: File?, name: String?): File? {
        if (root == null || name == null || name.isBlank()) return null
        if (name == "." || name == "..") return null
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null

        val candidate = File(name)
        if (candidate.isAbsolute) return null

        return try {
            val canonicalRoot = root.canonicalFile
            val canonicalCandidate = File(canonicalRoot, name).canonicalFile
            canonicalCandidate.takeIf { isWithin(canonicalRoot, it, false) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Creates one safe leaf without replacing an existing file.
     */
    @JvmStatic
    fun createNewLeaf(root: File?, name: String?): File? {
        val candidate = resolveLeaf(root, name) ?: return null
        return try {
            candidate.takeIf { it.createNewFile() }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Resolves [candidate] under [root], returning its canonical file only when valid.
     */
    @JvmStatic
    fun resolveWithin(root: File?, candidate: File?, allowRoot: Boolean): File? {
        if (!isWithin(root, candidate, allowRoot)) return null
        return try {
            candidate?.canonicalFile
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
