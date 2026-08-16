<div align="center">

<img src="docs/logo.svg" width="120" alt="ProcessLens">

# ProcessLens

**A process observatory for Android that refuses to make things up.**

Watch what your phone is actually doing — processes, threads, CPU, memory, wake locks,
network, battery — and record an investigation you can scrub back through afterwards.

### [⬇&nbsp; Download ProcessLens.apk](https://github.com/Dreamucxe/ProcessLens/releases/latest/download/ProcessLens.apk)

<sub>Android 8.0 or newer · 12 MB · no account, no network permission</sub>

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![Tests](https://img.shields.io/badge/unit%20tests-396%20passing-brightgreen)
![No network](https://img.shields.io/badge/INTERNET%20permission-none-informational)

</div>

---

## The idea

Every system monitor on Android runs into the same wall. Since Android 7 an app can no
longer read `/proc` for processes it does not own, and each release since has closed
another door. Most apps respond by quietly showing you a plausible number anyway.

ProcessLens does the opposite. Every reading it displays is wrapped in a type that can
only be one of three things:

```kotlin
sealed interface Observed<out T> {
    data class Value<out T>(val value: T, val source: DataSource, val precision: Precision)
    data class Restricted(val reason: RestrictionReason, val unlockedBy: AccessLevel?, val detail: String)
    data class Failed(val detail: String, val cause: String?)
}
```

There is no fourth case, and no default. If the platform will not tell ProcessLens
something, the screen says so — which permission or access level would reveal it, and why
it is hidden. A dash is never a zero, and the app says as much where you might mistake one
for the other.

The same rule governs the analysis. A spike that lines up with an app waking the device is
reported as a **potential correlation**, never a cause, because timestamps that happen to
agree are not proof. Battery figures the platform does not expose are absent rather than
estimated.

## What it does

**Watch** — a live dashboard of CPU, memory, storage and battery, refreshing every 1, 2, 5
or 10 seconds, or only when you ask. Per-core utilisation and clock speeds where the kernel
exposes them, load average, thermal zones.

**Inspect** — the full process list with sorting and filtering, then per-process detail:
state, UID, start time, CPU time, threads, and the parent/child tree. Installed apps with
their components, requested permissions and grant state, and which of their services are
running right now.

**Investigate** — start a recording, use your phone normally, then stop. ProcessLens
samples throughout and gives you a timeline of colour-dotted events: resource spikes
against thresholds you set, wake locks acquired, processes appearing and vanishing. Scrub
back through it, compare two apps side by side, and export the whole thing as JSON, CSV or
plain text.

**Understand its own limits** — a Capabilities screen listing all 37 metrics ProcessLens
knows how to read, grouped into nine areas and each marked available or not *on your
device*, computed at runtime from what actually works rather than assumed from a version
number.

## Access levels

Most of the app works with no special access at all. Two optional paths widen what the
kernel will admit to:

| | Reads |
|---|---|
| **Normal** | Own process in full, device-wide CPU and memory, battery, network state, installed packages, running services, usage stats with permission |
| **[Shizuku](https://shizuku.rikka.app/)** | Adds ADB-level shell observation: other processes' CPU and memory, threads, the real process tree, wake locks, per-app battery |
| **Root** | Adds unrestricted `/proc` and full `dumpsys` |

Shizuku is not root and ProcessLens does not pretend otherwise — it distinguishes all six
states it can be in (not installed; installed but not running; running with permission not
yet requested; running with permission denied; running with permission granted; running an
unsupported version) and tells you which capabilities each would unlock.

There is no "kill everything" button, no arbitrary shell, and no one-tap destructive
command. Process Details will not offer you a bare **Kill** button, because a tool for
understanding a system should not make it trivially easy to break one.

## Privacy

Not a policy — a property of the build.

- **`android.permission.INTERNET` is not in the manifest.** The app cannot open a socket
  even if it wanted to. Verified absent from the shipped APK.
- No account, no sign-in, no cloud, no analytics, no telemetry, no crash reporting service.
- Recordings, favourites and settings live in a local Room database and never leave the
  device unless you export a file and share it yourself.
- If the app ever crashes it writes the stack trace to your `Downloads` folder — a plain
  text file, yours to read, delete or send on.

## Build it yourself

```bash
git clone https://github.com/Dreamucxe/ProcessLens.git
cd ProcessLens
chmod +x gradlew
./gradlew assembleRelease
```

Requires JDK 17 and an Android SDK with platform 34 and build-tools 34.0.0.

`gradle.properties` is committed exactly as it was when the released APK was built —
on an aarch64 Android device under proot, not a workstation. That is why it pins
`org.gradle.java.home`, overrides `aapt2` with a native ARM binary, disables the Gradle
daemon and runs the Kotlin compiler in-process. On a normal machine, delete those four
lines.

Release builds are signed only if you drop a `keystore.properties` next to `settings.gradle.kts`:

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without it you get an unsigned APK; the signing key for the published release is not in
this repository.

## Tests

```bash
./gradlew testDebugUnitTest          # 396 JVM tests
./gradlew connectedDebugAndroidTest  # 90 instrumented tests, needs a device
```

The unit tests cover the `/proc` parser against fixture filesystems, CPU rate sampling,
spike detection, export rendering, investigation recording and the search, sort and filter
logic. The instrumented tests drive the real UI and deliberately use **no** `testTag`
anywhere in production code — every node is found by its visible text or its content
description, so a test that cannot find something is also telling you a screen reader
could not.

## Architecture

```
core/
  common          Observed<T>, AccessLevel, formatters, fuzzy matching
  designsystem    theme, glass cards, charts, bottom sheets
  system          SystemObserver + Standard / Shizuku / Root implementations
  permissions     runtime and special-access checks
data/
  database        Room entities, DAOs, migrations, exported schemas
  repository      repository implementations
domain/
  model           Capability, ProcessInfo, Investigation, UserSettings
  usecase         spike detection, recording, summarising, export
feature/          19 screens, one package each, MVVM with Hilt
```

Kotlin · Jetpack Compose · Material 3 · MVVM · Hilt · Room · Coroutines. The UI never
calls a shell command or a privileged API directly; everything goes through
`SystemObserver`, which routes to the highest access level currently available and falls
back cleanly when it is withdrawn.

## Licence

MIT — see [LICENSE](LICENSE).
