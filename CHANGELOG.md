# Changelog

All notable changes to Break Overlay are documented in this file.

## 1.0.1 — 2026-10-22

- Added MIT licensing, third-party notices, and full license texts.
- Packaged notices and the Apache-2.0 text inside the APK at
  `assets/licenses/`.
- Added real emulator screenshots and refreshed the README with installation,
  usage, build, signing, limits, and compliance sections.
- Added a changelog and release checksums.
- No behavior changes to break scheduling, overlays, or daily limits.

## 1.0.0 — 2026-10-21

- Initial public release.
- Foreground app monitoring with Android UsageStats.
- Active-time break scheduling with configurable interval and snooze.
- Full-screen overlay with animated title, eight-second countdown, progress bar,
  and optional notification chime.
- All-apps or selected-apps monitoring with search and sorting.
- Per-app daily limits and one notification per app per local day.
- DataStore persistence, boot recovery, and real permission-state rechecks.
- 59 unit tests covering scheduling, usage reconstruction, limits, and
  preferences.
