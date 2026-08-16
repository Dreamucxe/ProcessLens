package com.processlens

import android.app.Application
import android.content.Context
import com.processlens.core.system.CrashReporter
import dagger.hilt.android.HiltAndroidApp

/**
 * The application object.
 *
 * Deliberately almost empty. `Application.onCreate` runs on the main thread before the
 * first frame, so anything expensive here is startup latency the user pays every launch —
 * and Section 43 asks that ProcessLens not become the sort of app it is built to find.
 * Hilt constructs its object graph lazily, the database opens on first use, and system
 * observation starts when a screen asks for it.
 *
 * The one thing that does happen eagerly is the crash handler, and it is installed in
 * [attachBaseContext] rather than `onCreate` because of the order Android starts a
 * process in: `attachBaseContext`, then every content provider declared in the manifest,
 * then `onCreate`. A provider that cannot be constructed therefore kills the process
 * before `onCreate` is ever reached, and a handler installed there would never see it.
 */
@HiltAndroidApp
class ProcessLensApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Earliest app code in the process. Costs a handler registration and no I/O
        // unless something actually crashes.
        CrashReporter.install(base)
    }
}
