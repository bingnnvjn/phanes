package com.gph.fable.filepicker

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.gph.fable.R
import com.gph.fable.shared.file.SafeFilePaths
import com.gph.fable.shared.termux.TermuxConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.LinkedList
import java.util.Locale

open class FableDocumentsProvider : DocumentsProvider() {

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(BASE_DIR))
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(BASE_DIR))
        row.add(Root.COLUMN_SUMMARY, null)
        row.add(
            Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
        )
        row.add(Root.COLUMN_TITLE, context!!.getString(R.string.application_name))
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
        row.add(Root.COLUMN_AVAILABLE_BYTES, BASE_DIR.freeSpace)
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also {
            includeFile(it, documentId, null)
        }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        parent.listFiles()?.forEach { file ->
            if (SafeFilePaths.isWithin(BASE_DIR, file, false)) includeFile(result, null, file)
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = getFileForDocId(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun onCreate(): Boolean = true

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = getFileForDocId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory: $parentDocumentId")

        var newFile = SafeFilePaths.resolveLeaf(parent, displayName)
            ?: throw FileNotFoundException("Invalid document name")
        if (!SafeFilePaths.isWithin(BASE_DIR, newFile, false)) {
            throw FileNotFoundException("Invalid document name")
        }

        var noConflictId = 2
        while (newFile.exists()) {
            newFile = SafeFilePaths.resolveLeaf(parent, "$displayName (${noConflictId++})")
                ?: throw FileNotFoundException("Invalid document name")
        }
        try {
            val succeeded = if (Document.MIME_TYPE_DIR == mimeType) {
                newFile.mkdir()
            } else {
                newFile.createNewFile()
            }
            if (!succeeded) {
                throw FileNotFoundException("Failed to create document with id ${newFile.path}")
            }
        } catch (_: IOException) {
            throw FileNotFoundException("Failed to create document with id ${newFile.path}")
        }
        return newFile.path
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file == SafeFilePaths.resolveWithin(BASE_DIR, BASE_DIR, true)) {
            throw FileNotFoundException("Cannot delete the Fable home root")
        }
        if (!file.delete()) throw FileNotFoundException("Failed to delete document with id $documentId")
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String = getMimeType(getFileForDocId(documentId))

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(
        rootId: String,
        query: String?,
        projection: Array<String>?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(rootId)
        val normalizedQuery = query?.lowercase(Locale.ROOT) ?: ""
        val pending = LinkedList<File>()
        pending.add(parent)

        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            if (SafeFilePaths.isWithin(BASE_DIR, file, true)) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { pending.add(it) }
                } else if (file.name.lowercase(Locale.ROOT).contains(normalizedQuery)) {
                    includeFile(result, null, file)
                }
            }
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        try {
            SafeFilePaths.isWithin(
                getFileForDocId(parentDocumentId),
                getFileForDocId(documentId),
                false
            )
        } catch (_: FileNotFoundException) {
            false
        }

    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, docId: String?, suppliedFile: File?) {
        var resolvedDocId = docId
        var file = suppliedFile
        if (resolvedDocId == null) {
            resolvedDocId = getDocIdForFile(file!!)
        } else {
            file = getFileForDocId(resolvedDocId)
        }

        var flags = 0
        if (file!!.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        val parent = file.parentFile
        if (parent != null && SafeFilePaths.isWithin(BASE_DIR, parent, true) && parent.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }

        val mimeType = getMimeType(file)
        if (mimeType.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, resolvedDocId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    companion object {
        private const val ALL_MIME_TYPES = "*/*"
        private val BASE_DIR = TermuxConstants.TERMUX_HOME_DIR
        private const val MAX_SEARCH_RESULTS = 50

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private fun getDocIdForFile(file: File): String {
            val safeFile = SafeFilePaths.resolveWithin(BASE_DIR, file, true)
                ?: throw IllegalArgumentException("File is outside Fable home: $file")
            return safeFile.absolutePath
        }

        @Throws(FileNotFoundException::class)
        private fun getFileForDocId(docId: String): File {
            val file = SafeFilePaths.resolveWithin(BASE_DIR, File(docId), true)
                ?: throw FileNotFoundException("Document is outside Fable home")
            if (!file.exists()) throw FileNotFoundException("${file.absolutePath} not found")
            return file
        }

        private fun getMimeType(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR

            val name = file.name
            val lastDot = name.lastIndexOf('.')
            if (lastDot >= 0) {
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(lastDot + 1).lowercase(Locale.getDefault()))
                    ?.let { return it }
            }
            return "application/octet-stream"
        }
    }
}
