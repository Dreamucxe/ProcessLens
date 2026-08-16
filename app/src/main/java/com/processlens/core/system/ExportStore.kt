package com.processlens.core.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.processlens.domain.usecase.ExportDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes exports to disk and hands them to other apps by content URI (Section 26).
 *
 * Files go to `cacheDir/exports`, which needs no storage permission on any API level
 * from 26 upwards and which the system may reclaim — appropriate for an artefact whose
 * durable copy is the recording still in the database.
 *
 * Sharing goes through [FileProvider], never a `file://` path. A `file://` URI handed
 * to another app throws `FileUriExposedException` from API 24 on, and the content URI
 * additionally scopes the grant to the one app the user picks, for the length of that
 * one action.
 */
@Singleton
class ExportStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Where an export ended up, and how to offer it onward. */
    data class Written(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
        /** Path shown to the user. Kept short — the absolute cache path is noise. */
        val displayPath: String,
    )

    private val directory: File
        get() = File(context.cacheDir, DIRECTORY)

    /**
     * Writes [document], replacing any previous export with the same name.
     *
     * Old exports are pruned first. This directory is a scratch space, and a user who
     * exports the same recording repeatedly should not accumulate copies they never
     * asked to keep.
     */
    fun write(document: ExportDocument): Written {
        val dir = directory
        if (!dir.exists() && !dir.mkdirs()) {
            error("Could not create the export directory in this app's cache.")
        }
        prune(dir, keepName = document.fileName)

        val file = File(dir, document.fileName)
        file.writeText(document.content)

        return Written(
            file = file,
            fileName = document.fileName,
            mimeType = document.mimeType,
            byteCount = file.length(),
            displayPath = "Android cache · " + DIRECTORY + "/" + document.fileName,
        )
    }

    /**
     * A share intent for an already-written export.
     *
     * Callers must respect the local-only setting before calling this: the switch
     * exists so an export can be produced without any chance of it leaving the device,
     * and this class does not read settings.
     */
    fun shareIntent(written: Written): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            written.file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = written.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, written.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share export").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Deletes every export written so far. Offered in Settings as a data control. */
    fun clear(): Int {
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        files.forEach { if (it.isFile && it.delete()) deleted++ }
        return deleted
    }

    /** Total bytes currently held in the export directory. */
    fun usedBytes(): Long =
        directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun prune(dir: File, keepName: String) {
        val files = dir.listFiles() ?: return
        files.forEach { if (it.isFile && it.name != keepName) it.delete() }
    }

    private companion object {
        /** Must match the `cache-path` in `res/xml/file_paths.xml`. */
        const val DIRECTORY = "exports"
    }
}
