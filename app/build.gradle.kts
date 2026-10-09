import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.processlens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.processlens"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
        testInstrumentationRunner = "com.processlens.HiltTestRunner"

        // Room exports its schemas here so migrations can be tested against the
        // real historical schema rather than a hand-written approximation.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    // Optional release signing: reads `keystore.properties` when present so that
    // `assembleRelease` emits a signed, directly installable APK.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystore = keystorePropsFile.exists()
    val keystoreProps = Properties().apply {
        if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // R8 and resource shrinking are on for release by default. An unminified,
    // unobfuscated release APK is a shipping defect rather than a build-host
    // preference, so the default does not bend to the host that happens to be
    // building. The on-device ARM host this project is often built on cannot run
    // R8 inside its ~1.8 GB of free RAM, so it opts out in GRADLE_USER_HOME with
    // `processlens.minify=false`, which keeps the opt-out on that one machine and
    // leaves every other build — CI included — minified and shrunk.
    val minifyRelease = (project.findProperty("processlens.minify") as String?)
        ?.toBooleanStrictOrNull() ?: true

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Resource shrinking requires code shrinking, so the two move together.
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.time.ExperimentalTime")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    lint {
        // Lint is run as its own Gradle invocation on this build host rather than as part
        // of `assembleRelease`. It is not a saving of work: the same checks run, in a JVM
        // of their own. With the Kotlin compiler running in-process and no daemon, one
        // invocation that compiles three variants *and* runs lint's full analysis exhausts
        // metaspace, and the failure lands on whichever task is unlucky rather than on the
        // one that is actually too big.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.window)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)

    // Shizuku: elevated (ADB-level) observation where the user has it running.
    //
    // Both must be `implementation`. `provider` supplies rikka.shizuku.ShizukuProvider,
    // which the manifest declares — and Android instantiates every declared provider
    // while the process is starting, before Application.onCreate runs. A compileOnly
    // dependency here builds and installs cleanly and then kills the app on launch with
    // ClassNotFoundException, because the class is not in the APK.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Local JVM tests (Section 56).
    //
    // Deliberately limited to JUnit and coroutines-test. Everything under src/test is
    // written against the pure, Android-free seams — the /proc parser with an injected
    // filesystem root, the CPU rate sampler, the spike detector, the export renderer,
    // the process arranger — so no Android framework double is needed and these tests
    // run on any JDK without a device or an emulator.
    //
    // Robolectric, mockk and Truth are intentionally absent: they would add a large
    // download to the build for tests that are better placed under androidTest, where
    // the real framework runs them instead of a simulation of it.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
