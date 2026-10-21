# Moving from Ceiling Scout to Core Filter Pilot

The repository's default branch now contains Core Filter Pilot at its root. The previous farmer, offline mapper and laptop tools remain available in Git history and the [v0.6.2 release](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/tag/v0.6.2). Existing local work is not needed to build Pilot.

## Install the new application

Download [Core Filter Pilot 1.0.0-rc1](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/tag/v1.0.0-rc1). This is a release candidate requiring supervised phone validation.

Pilot uses Android package **`dev.corefilter.pilot`**. Ceiling Scout used **`com.corefilter.farmer`**. They install separately; this APK cannot replace the older installation through its updater. Pause or disable the older automation before enabling Pilot so both services do not issue game input.

Pilot does not import old captures, maps, equipment assumptions or calibration. In Corebound 1.2.1.1 / build 140, choose the left/right button scheme and Lost Scrapyard, enter a run, then use Pilot's floating **Calibrate** control to select the five points. Configure only the air jumps supported by the actual equipped build and press **Run**. See the [README](../README.md) for setup and controls.

## Source and build layout

Run Gradle from the repository root. `engine/` contains platform-independent observation and navigation logic; `app/` contains the Android application. Build/format/sign/package helpers are under `tools/`. There is no laptop server, Unity injection, private game-state API or dependency on the old source tree.

Public CI checks Java formatting, the engine and Android tests, source packaging, lint and the minimized release build. Signing keys are never included in the repository, CI artifacts or source ZIP. The release APK is signed separately; archive and APK SHA-256 hashes accompany it as release assets.

## Release status

The RC1 release is the newest Pilot build. GitHub reserves its **Latest** designation for full releases, so clients using `/releases/latest` may still receive Ceiling Scout v0.6.2. Use the explicit Pilot release link above.

The tested local/recorded behavior does not establish live reward collection, a measured farming rate, or complete exploration of procedurally joined rooms. Those are acceptance gates for a stable production release, not claims made by this candidate.
