package com.processlens

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps in Hilt's test [Application].
 *
 * `app/build.gradle.kts` names this class as the `testInstrumentationRunner`, and it
 * exists because [ProcessLensApplication] is annotated `@HiltAndroidApp`: the real
 * application builds the production component, which cannot accept the test module
 * replacements an instrumentation test installs. [HiltTestApplication] builds a
 * component that can.
 *
 * Nothing else is substituted. The tests below this runner talk to the real
 * `ActivityManager`, the real `PackageManager`, the real `/proc` and the real Room
 * database on the device, because the whole point of the Section 56/57 suite is to
 * verify what the platform actually returns at the API level the test is running on —
 * a mock of `getRunningAppProcesses()` would happily report the pre-API-28 behaviour
 * on an API 34 device and prove nothing.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
