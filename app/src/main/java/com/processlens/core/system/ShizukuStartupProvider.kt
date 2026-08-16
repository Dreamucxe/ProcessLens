package com.processlens.core.system

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.util.Log
import rikka.shizuku.ShizukuProvider

/**
 * The Shizuku handshake provider, wrapped so that it cannot stop ProcessLens starting.
 *
 * Shizuku delivers its binder by calling into a content provider the app declares, so the
 * provider has to exist for Shizuku support to work at all. The manifest points here
 * rather than at [ShizukuProvider] directly because of what Android does with a provider
 * that throws: content providers are constructed while the process is starting, before
 * `Application.onCreate`, and an exception from one of them is fatal — the app dies on
 * launch, before a line of ProcessLens has run, with no screen and no way to recover.
 *
 * `ShizukuProvider.onCreate` can throw. It asks Sui whether it is present, and that path
 * ends at `android.os.ServiceManager.getService`, reached by reflection. When hidden-API
 * enforcement denies that member the reflected `Method` is left null and the resulting
 * `NullPointerException` is not caught anywhere on the way back out. Whether it is denied
 * depends on the Android version, the vendor and the target SDK — which is to say it is
 * exactly the kind of thing that cannot be assumed from a build-time constant.
 *
 * So the whole of it is guarded. Section 27 treats Shizuku as one of several access
 * levels, not a requirement: if this initialisation fails, ProcessLens starts normally
 * and reports Shizuku as unavailable through the same capability system that handles a
 * device where Shizuku was never installed. An optional elevation path is not permitted
 * to be a single point of failure for the app.
 *
 * Shizuku itself is unaffected by the subclassing. Its server finds this provider by
 * authority — `${applicationId}.shizuku`, declared in the manifest — and not by class
 * name, and the inherited `call` implementation still performs the binder exchange.
 */
class ShizukuStartupProvider : ShizukuProvider() {

    override fun attachInfo(context: Context, info: ProviderInfo) {
        // ShizukuProvider attaches the context and only then validates the manifest entry,
        // so by the time it can object the provider is already usable — swallowing the
        // objection loses nothing but the crash. Ours declares multiprocess=false and
        // exported=true, which is what it asks for, so this should not trigger.
        try {
            super.attachInfo(context, info)
        } catch (error: Throwable) {
            Log.w(TAG, "Shizuku provider rejected its manifest entry", error)
        }
    }

    override fun onCreate(): Boolean {
        try {
            super.onCreate()
        } catch (error: Throwable) {
            // The likely cause is Sui initialisation reaching a hidden API. Shizuku ends
            // up reported as unavailable, which is a state the app already handles.
            Log.w(TAG, "Shizuku initialisation failed; continuing without it", error)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        try {
            super.call(method, arg, extras)
        } catch (error: Throwable) {
            // A throw here happens inside a binder call from the Shizuku server rather
            // than at startup, but it would still surface as a crash. Failing the
            // handshake quietly leaves Shizuku unavailable and the app intact.
            Log.w(TAG, "Shizuku call failed: $method", error)
            null
        }

    private companion object {
        const val TAG = "ProcessLens"
    }
}
