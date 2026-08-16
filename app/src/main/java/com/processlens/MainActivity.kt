package com.processlens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.processlens.core.designsystem.ProcessLensTheme
import com.processlens.feature.navigation.ProcessLensNavHost
import com.processlens.feature.onboarding.OnboardingOverlay
import com.processlens.feature.settings.AppShellViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity (Sections 2, 51, 52).
 *
 * The splash screen is installed and then allowed to leave immediately. Section 52
 * asks for a short splash with a subtle lens animation and explicitly forbids
 * delaying startup, so there is no `setKeepOnScreenCondition` holding it while
 * settings load: the shell renders as soon as Compose is ready and the theme
 * transitions in place when the persisted settings arrive a frame or two later.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ProcessLensApp() }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun ProcessLensApp() {
    val shell: AppShellViewModel = hiltViewModel()
    val state by shell.state.collectAsStateWithLifecycle()

    ProcessLensTheme(settings = state.settings) {
        // Compact width gets the bottom bar, everything wider gets the rail
        // (Section 33). Measured from the window rather than from a screen-size
        // qualifier so it is correct in split-screen and on a folding device.
        val widthClass = calculateWindowSizeClass(LocalActivity.current).widthSizeClass
        val isCompact = widthClass == WindowWidthSizeClass.Compact

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ProcessLensNavHost(
                isCompact = isCompact,
                isRecording = state.isRecording,
            )

            // Section 53's minimal onboarding: an overlay over the real, already-live
            // app rather than a blocking setup flow. Nothing is gated behind it and
            // no permission is requested from it.
            if (state.showOnboarding) {
                OnboardingOverlay(
                    accessSummary = state.accessSummary,
                    onDismiss = shell::completeOnboarding,
                )
            }
        }
    }
}

/**
 * The hosting activity, for [calculateWindowSizeClass].
 *
 * `LocalContext.current as Activity` is the usual incantation and it crashes inside
 * a Compose preview, where the context is not an activity. This resolves it safely.
 */
private object LocalActivity {
    val current: android.app.Activity
        @Composable get() {
            var context = androidx.compose.ui.platform.LocalContext.current
            while (context is android.content.ContextWrapper) {
                if (context is android.app.Activity) return context
                context = context.baseContext
            }
            error("ProcessLensApp must be hosted in an Activity")
        }
}
