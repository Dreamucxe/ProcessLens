package com.processlens.core.system

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an uncaught exception to a file the user can actually read.
 *
 * A process observatory that dies silently is worse than useless, and the usual answer —
 * "check logcat" — is not available to someone holding a phone: since Android 4.1 an app
 * may read only its own log entries, so a crash is invisible to every tool on the device.
 * This is the local, offline substitute. On a crash, ProcessLens writes the stack trace
 * to `Downloads/ProcessLens-crash-<timestamp>.txt`, which the user can open, keep or send
 * on deliberately.
 *
 * Nothing is uploaded and there is no reporting endpoint (Section 29): the file is written
 * with the platform's own MediaStore API and then the app is out of the loop. Downloads is
 * the destination rather than the app's private storage precisely because private storage
 * is unreadable to the person trying to diagnose the problem.
 *
 * Two details matter for it to be worth anything:
 *
 * It is armed from `Application.attachBaseContext`, the earliest point at which app code
 * runs. Content providers declared in the manifest are instantiated after that and before
 * `Application.onCreate`, so a provider that fails to load — a class missing from the APK,
 * a database that will not open — is caught here. A handler installed in `onCreate` would
 * miss exactly those failures.
 *
 * And it chains to the handler it replaced, so the platform still records the crash and
 * still shows the user its dialog. Swallowing the exception would leave the app wedged in
 * a half-dead state, which is a worse outcome than the crash.
 */
object CrashReporter {

    private const val PREFIX = "ProcessLens-crash-"
    private const val DIRECTORY = "crashes"

    @Volatile
    private var installed = false

    /**
     * Installs the handler. Safe to call more than once; only the first call takes effect.
     *
     * [context] should be the application context. It is held for the life of the process,
     * which is the intended lifetime of the handler.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Every step is guarded. An exception raised inside a crash handler replaces a
            // diagnosable failure with an undiagnosable one, so this must not be the thing
            // that throws.
            runCatching { write(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report text, kept separate from where it goes so it can be tested directly. */
    fun report(thread: Thread, error: Throwable, now: Date, device: String): String =
        buildString {
            appendLine("ProcessLens crash report")
            appendLine("========================")
            appendLine()
            appendLine("This file was written on this device by ProcessLens when it crashed.")
            appendLine("Nothing was uploaded: the app has no INTERNET permission and no")
            appendLine("backend. Delete it, or send it on yourself if you want it looked at.")
            appendLine()
            appendLine("When:    " + TIMESTAMP.get()!!.format(now))
            appendLine("Thread:  " + thread.name)
            appendLine("Device:  " + device)
            appendLine()
            appendLine("Exception")
            appendLine("---------")
            appendLine(stackTraceOf(error))
        }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val now = Date()
        val name = PREFIX + FILE_STAMP.get()!!.format(now) + ".txt"
        val text = report(thread, error, now, describeDevice(context))

        // The app's own storage first: it cannot fail for want of a permission or a
        // volume, so there is always one copy even if the shared-storage write is refused.
        runCatching {
            val dir = File(context.filesDir, DIRECTORY)
            if (dir.exists() || dir.mkdirs()) File(dir, name).writeText(text)
        }

        // Then the copy the user can find. MediaStore needs no permission from API 29;
        // below that, an app may write its own external files directory without one.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(context, name, text)
            } else {
                val dir = context.getExternalFilesDir(null)
                if (dir != null && (dir.exists() || dir.mkdirs())) {
                    File(dir, name).writeText(text)
                }
            }
        }
    }

    private fun writeToDownloads(context: Context, name: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    /**
     * The full chain, causes included.
     *
     * `Throwable.printStackTrace` already walks causes, and it is the one formatting of a
     * stack trace every Android developer can read at a glance, so this reuses it rather
     * than inventing a layout.
     */
    private fun stackTraceOf(error: Throwable): String {
        val writer = java.io.StringWriter()
        java.io.PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun describeDevice(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "ProcessLens " + info.versionName
        }.getOrDefault("ProcessLens")

        return version + " · " + Build.MANUFACTURER + " " + Build.MODEL +
            " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
            " · " + Build.SUPPORTED_ABIS.joinToString("/")
    }

    /**
     * `SimpleDateFormat` is not thread-safe and a crash can arrive on any thread, so each
     * thread gets its own. `Locale.US` because these are file names and log lines, not
     * text shown in the UI: a stable, machine-sortable stamp beats a localised one.
     */
    private val FILE_STAMP = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
    }

    private val TIMESTAMP = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    }
}
