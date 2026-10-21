# Break Overlay

Break Overlay is a local-only Android eye-break and screen-time awareness app. It watches the
foreground app with Android's UsageStats APIs and shows a full-screen, eight-second rest overlay
after a configurable amount of focused use. It never closes, force-stops, or blocks another app.

- **Package:** `com.breakoverlay.app`
- **Version:** `1.0.0` (versionCode 1)
- **Minimum Android version:** Android 7.0 / API 24
- **Compile and target SDK:** API 34
- **Language and UI:** Kotlin, Material Components, XML layouts, View Binding

## Download

The signed release APK is attached to the
[latest GitHub release](https://github.com/SaiKishore6336/BreakOverlay/releases/latest) as
`app-release.apk`.

Source code: <https://github.com/SaiKishore6336/BreakOverlay>

## Current builds

Local release build output:

```text
app/build/outputs/apk/release/app-release.apk
```

- Package: `com.breakoverlay.app`
- Version: `1.0.0` (versionCode 1)
- APK Signature Scheme v2: verified
- Signing certificate SHA-256:
  `72:8C:F2:99:A0:49:11:1E:34:AC:4F:01:51:E3:99:E4:3F:E1:F0:8F:9E:0C:CA:E0:FC:52:2D:DD:FC:19:ED:94`
- SHA-256 of the built APK:
  `12e660c69c10070fc291ff1f151241dad483297e20234b5eae341b4d7988fc97`

The release APK is signed with a local, non-Play upload key and is intended for sideloading and
personal testing. It is not published on Google Play. Keep a secure backup of the keystore and its
passwords if you plan to publish updates: Android only accepts updates signed by the same key.

Install it on a connected Android device with:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

A debug-signed APK is also produced at
`app/build/outputs/apk/debug/app-debug.apk` for development. Do not mix debug and release builds
when updating an installed app; Android will reject an update signed by a different key.

If more than one device is connected, add `-s <serial>` after `adb`. Enable USB debugging and
accept the computer's RSA fingerprint on the phone first.

## Features

- Foreground monitoring through a user-controlled foreground service.
- Active-time break scheduling rather than wall-clock scheduling:
  - Time accrues only while the screen is on, the device is unlocked, and a monitored app is in
    the foreground.
  - Switching between eligible apps keeps the same continuous session.
  - Briefly leaving monitored apps (up to two minutes), such as a system dialog, pauses rather than
    resets the session. A longer gap or screen-off resets it.
- Full-screen break overlay with:
  - Three-stage animated title.
  - Eight-second monotonic countdown and progress bar.
  - Locked snooze button until the countdown completes.
  - Optional system notification chime.
  - Back-key blocking while the overlay is visible.
- Configurable break interval (5–240 minutes) and snooze length (1–60 minutes).
- “All apps” or “Selected apps only” monitoring scope, with searchable and sortable app selection.
- Per-app daily limits (1–1440 minutes) with one high-priority notification per app and local day.
- Daily usage reconstructed from Android `UsageEvents` and clipped to local midnight.
- Estimated usage is labelled with `~` when sessions are inferred or still open.
- Real permission state is rechecked whenever the dashboard resumes; no placeholder statuses.
- DataStore persistence for settings, selection, limits, notification deduplication, and the break
  schedule.
- Same-boot schedule recovery after service/process restarts. A cross-boot schedule snapshot is
  discarded because elapsed-time values cannot be compared across a reboot.
- Boot receiver restarts monitoring after reboot when the user had enabled it and critical
  permissions still exist. Android may reject the background start; the dashboard reports what it
  can verify.
- Local-only operation. No account, analytics, network permission, or cloud sync.

## How the break timer works

The timer is based on **eligible foreground active time**, not elapsed wall time.

1. The foreground service samples Android `UsageEvents` (normally every five seconds while the
   screen is on).
2. A session starts when a monitored app is eligible. The app must have usage access, the screen
   must be on, the device must be unlocked, and the app must be in the selected scope. Launchers,
   System UI, and Break Overlay itself are always excluded.
3. Small gaps (up to two minutes) pause accrual. Screen-off or a longer ineligible gap ends the
   session and clears its accumulated time.
4. When accumulated active time reaches the configured interval plus any snooze extension, the
   foreground service opens the full-screen overlay. Time does not accrue while the overlay is
   visible.
5. The snooze button is disabled until the eight-second rest completes. Snoozing closes the overlay
   and adds the configured snooze length to the next active-time threshold. It does not edit
   Android usage records or daily limits.
6. The foreground service resumes sampling after snooze. The first post-overlay tick establishes a
   new accrual boundary so the time spent looking at the overlay is not counted.

If monitoring cannot run (missing permission, screen off, lock screen, service stopped, battery
restrictions), the dashboard says so instead of inventing activity.

## Daily limits

A daily limit is a gentle awareness feature, not an app blocker. When an app's reconstructed usage
for the current local day reaches its configured limit, Break Overlay posts one notification for
that app and day. The notification includes the configured limit and the measured daily usage, and
marks the value as an estimate when appropriate. A new local day creates new notification
eligibility. Disabled or removed limits do not notify.

Android does not grant a normal third-party app the ability to forcibly close another app, so Break
Overlay deliberately does not pretend to do that.

## Overlay preview isolation

The dashboard's **Test overlay now** action shows the real overlay UI without affecting the real
break session:

- A shared in-process preview flag freezes the monitoring scheduler.
- The scheduler resets its accrual boundary on both preview start and preview end.
- Preview time is never charged to the real continuous session.
- Daily usage and daily-limit notification state are never written by a preview.
- A real overlay and a preview cannot be visible at the same time.

The preview still requires the “Display over other apps” permission and is dismissed when the
dashboard is destroyed.

## Permissions

| Permission | Why it is needed |
| --- | --- |
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | Shows the full-screen break overlay above the current app. |
| **Usage access** (`PACKAGE_USAGE_STATS`) | Reads foreground app events and reconstructs daily usage. |
| **Notifications** (`POST_NOTIFICATIONS`, Android 13+) | Shows the ongoing monitoring notification and daily-limit alerts. |
| **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Keeps monitoring alive while the app is not open. |
| **Boot completed** (`RECEIVE_BOOT_COMPLETED`) | Best-effort restart of monitoring after a reboot. |
| **Battery optimization exemption** (optional) | Improves reliability on devices with aggressive background restrictions. The dashboard opens system settings; the app does not silently request an exemption. |

Usage access and overlay access are checked with the real system state every time the dashboard
returns to the foreground.

## First-run setup

1. Install and open Break Overlay.
2. Under **Permissions & access**, grant **Usage access**.
3. Grant **Display over other apps**.
4. Allow notifications when prompted (recommended for limit alerts and the ongoing status).
5. Optionally open battery optimization settings and allow unrestricted background use.
6. Choose **All apps** or **Selected apps only**. For selected mode, search for and check at least
   one app.
7. Adjust the break interval and snooze length, then tap **Apply break settings**.
8. Turn on **Monitoring**.
9. Tap **Test overlay now** to verify the overlay appearance and countdown.

Do not disable the foreground service notification. Android uses it to keep the monitoring service
alive.

## Build from source

### Requirements

- Linux, macOS, or Windows with a Bash-compatible shell for `build_apk.sh`
- JDK 17
- Android SDK with **platform 34** and matching build tools
- An `ANDROID_HOME` or `ANDROID_SDK_ROOT` environment variable, or an SDK at
  `$HOME/Android/Sdk`

The project uses Android Gradle Plugin 8.5.2, Kotlin 2.0.20, and the Gradle 8.7 wrapper.

### Get the source

```bash
git clone https://github.com/SaiKishore6336/BreakOverlay.git
cd BreakOverlay
```

The commands below assume the current directory is the repository root.

### One-command debug build

```bash
./build_apk.sh
```

The script uses the Gradle wrapper, builds the debug APK, verifies that the file exists, and prints
its path and SHA-256 when `sha256sum` is available.

### Release build and signing

Release signing is configured from a repository-root `keystore.properties` file. The real properties
file and the `keystore/` directory are ignored by Git; `keystore.properties.example` is the tracked
template. To create a fresh local signing key and build a signed APK:

```bash
mkdir -p keystore
keytool -genkeypair \
  -keystore keystore/breakoverlay-release.jks \
  -alias breakoverlay \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties
# Edit keystore.properties and enter the same passwords and alias you chose above.
./gradlew assembleRelease
```

The signed artifact is written to `app/build/outputs/apk/release/app-release.apk`. If
`keystore.properties` is absent, the release task produces an unsigned APK instead; it does not fall
back to a debug key. Verify a signed artifact with:

```bash
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

### Gradle commands

```bash
./gradlew testDebugUnitTest
./gradlew testReleaseUnitTest
./gradlew lintDebug
./gradlew lintRelease
./gradlew assembleDebug
./gradlew assembleRelease
```

Outputs:

- Release APK: `app/build/outputs/apk/release/app-release.apk`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unit-test reports: `app/build/reports/tests/testDebugUnitTest/index.html` and
  `app/build/reports/tests/testReleaseUnitTest/index.html`
- Lint reports: `app/build/reports/lint-results-debug.html` and
  `app/build/reports/lint-results-release.html`

## Verification

The project was validated in this development environment with:

- `./gradlew testDebugUnitTest` and `./gradlew testReleaseUnitTest` — 59 unit tests passing in each
  variant.
- `./gradlew lintDebug` and `./gradlew lintRelease` — 0 errors, 9 warnings. The remaining warnings
  are eight dependency-update notices and one large single-screen layout warning; none block the
  build.
- `./gradlew assembleDebug` — succeeds.
- `./gradlew assembleRelease` — succeeds and produces a signed `app-release.apk`.
- `apksigner verify --verbose --print-certs` — verifies the release APK with APK Signature Scheme
  v2. The package is `com.breakoverlay.app`, versionCode 1, versionName 1.0.0, minSdk 24, targetSdk
  34.

No emulator or physical Android device was connected in this environment. Device-specific behavior
(system permission screens, vendor battery management, actual overlay layering, notification
appearance, and real UsageStats event ordering) remains to be verified by installing the APK on a
phone. The app does not claim device testing that did not occur.

## Project layout

```text
app/src/main/java/com/breakoverlay/app/
├── MainActivity.kt                 Dashboard, permissions, app list, settings, preview
├── apps/AppListRepository.kt       Launchable-app discovery and exclusions
├── data/                           AppPreferences and DataStore repository
├── domain/                         Pure policies: limits, day keys, ranges, formatting, sorting
├── notifications/                  Channels and notification builders
├── overlay/                        Overlay lifecycle, countdown, sound, preview flag
├── receivers/BootReceiver.kt       Boot-time restart policy
├── scheduler/                      Break scheduler and boot recovery
├── service/                        Foreground monitoring loop and status bus
├── ui/AppListAdapter.kt            RecyclerView adapter
├── usage/                          UsageEvents reconstruction and repository
└── util/                           Permission and boot-count helpers

app/src/test/java/com/breakoverlay/app/
├── data/                            Preferences defaults and limit helpers
├── domain/                          Selection/search, limits, day boundaries, formatting, readiness
├── scheduler/                       Continuous sessions, snooze, preview isolation, boot recovery
└── usage/                           Session reconstruction, clipping, estimates, edge cases
```

## Known limitations

- An application overlay cannot cover the secure keyguard, permission dialogs, or secure system
  windows. Android controls what overlays may cover.
- Break timing is best effort. Doze, App Standby, aggressive OEM task managers, battery
  restrictions, and Android's background-start rules can delay or stop the foreground service.
  The dashboard distinguishes “monitoring enabled” from “service actually running”.
- Android may stop a foreground service. The app uses `START_STICKY`, boot recovery, and a
  user-visible notification, but it cannot guarantee resurrection on every vendor build.
- UsageStats is not a perfect real-time API. Reconstructed totals can differ from Digital
  Wellbeing, especially for sessions that began before the query window, inferred unlock sessions,
  or sessions still open at query time. Estimated totals are marked with `~`.
- Daily limits produce a notification; they do not and cannot force-close another app.
- The overlay's chime follows the device's notification sound and volume. Silent mode, Do Not
  Disturb, and a silent notification sound are respected.
- The eight-second countdown is deliberately brief. If the user does not tap the snooze button
  after it completes, the overlay remains on screen until the user snoozes or monitoring is stopped.
- The release APK is not minified and is signed with a local upload key rather than a Play Store
  signing key.
- No connected device or emulator was available during development, so physical-device testing is
  still required.

## Troubleshooting

**Monitoring turns off immediately or says the service is stopped**

Check Usage access and Display over other apps. Some Android 12+ builds reject foreground-service
starts from the background; open the dashboard and toggle monitoring again. On Android 14, the
declared `specialUse` foreground-service type and its subtype property must remain in the manifest.

**Breaks do not appear**

Confirm the screen is on and unlocked, monitoring is on, the foreground app is in the selected
scope, and the overlay permission is still granted. Use **Test overlay now** to verify overlay
rendering. Battery optimization restrictions can pause background monitoring; exempt the app if
your device requires it.

**Usage is lower or higher than Android Digital Wellbeing**

Usage is reconstructed from `UsageEvents` between local midnight (with a 24-hour lookback) and the
current time. Open or inferred sessions are estimates. Missing pause/resume events tend to
under-count rather than double count, but OEM event streams vary.

**Notifications do not appear**

Grant the Android 13+ notification permission, enable app notifications, and check that the
**Gentle alerts** notification channel is not disabled. The ongoing **Monitoring status** channel is
separate and low priority.

## Privacy

Settings, selected app package names, daily limits, notification deduplication keys, and the break
schedule are stored locally with AndroidX DataStore. Usage data is read from Android's UsageStats
provider and is not uploaded. The app declares no network permission and contains no analytics or
advertising SDK.
