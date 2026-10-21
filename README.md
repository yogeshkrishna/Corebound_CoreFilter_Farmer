# Core Filter Pilot

[![Pilot checks](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/workflows/check.yml/badge.svg?branch=main)](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/workflows/check.yml)

**[Download 1.0.0-rc1](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/tag/v1.0.0-rc1)** · [Moving from Ceiling Scout](docs/MIGRATION.md)

An independent Android application for a calibrated, closed-loop Lost Scrapyard farming patrol. This is **1.0.0-rc1**: the APK builds and the automated checks and recorded-image replay are exercised; **live farming and Core Filter acquisition have not been verified on a connected phone**. It should receive a supervised phone trial before unattended use or a production-release claim.

## Install and run

Use the signed `CoreFilterPilot-1.0.0-rc1.apk` from the release package. Install it alongside the previous farmer: its package is `dev.corefilter.pilot`, so it does not replace or read that application's storage.

1. Open Pilot and enable **Core Filter Pilot controls** under Android Accessibility. A sideloaded app may first require **App info → menu → Allow restricted settings**. Pilot explains its screen-observation and touch capabilities before opening Settings.
2. In Corebound **1.2.1.1**, use the **left/right button** control scheme, equip your chosen contact-damage build and select **Lost Scrapyard** with your chosen boost. Enter a run. This application does not buy equipment or change boosts.
3. From the floating bar, tap **Calibrate**. On the frozen image, choose the left button, right button, a clear jump area, the center of the player's textured body/core, and an interior patch of the **main solid floor**. Use a patch away from edges, platforms and background scenery. Save and return to the game.
4. Set the actual number of **additional air jumps** in Pilot if your equipment provides them. The default is zero; seven jumps are not assumed. Press **Run** on the floating bar.
5. **Pause/Resume** preserves the session counters and rechecks the current screen. **Stop** ends the session and hides the bar. Reopen Pilot → **Show floating controls** to bring it back.

Recalibrate after changing the hull/core appearance, controls, game display layout or aspect ratio. Normal touch input can cancel an automated gesture; repeated cancellations pause the session. An issued touch lasts at most the configured pulse (160 ms by default); stopping prevents further touches and lets that finite pulse release.

## What the program does

- Captures the game through Android's accessibility screenshot API; performs sampled normalized-correlation player tracking, calibrated foreground probes and camera-motion estimation locally at a 640-pixel analysis width.
- Patrols visible corridors with separate movement and jump touches, checks floor/ceiling observations, respects the configured air-jump request budget, turns during bounded stuck recovery and pauses if perception remains uncertain.
- Uses the recovered camera scale, including wide-screen correction, rather than hardcoded recording coordinates. Physical tile units are independent of the 192-pixel source artwork.
- Recognizes English result screens, confirms them with two distinct fresh text observations, selects a verified **Continue/Retry** button, and can select Lost Scrapyard and press **PLAY** only when its actual description confirms selection. Menu taps require observed transitions and have bounded retries. Paid/unrelated confirmations pause.
- Optionally accepts a recognized free **Watch Ad?** dialog. It closes an ad only when both completion text and a **Close** button are positively recognized. Unknown SDK formats and external store screens require manual handling. It does not automatically select unverified bonus offers.
- Runs one capture/vision operation and one finite gesture at a time, never a blind route of queued inputs. OCR is single-flight and at most once per second. Capture cadence adapts downward if measured vision CPU cost is high. Paused/stopped sessions perform no polling.
- Keeps a bounded local diagnostic history. **Share local diagnostics** opens Android's share chooser; the report contains state/action summaries, not screenshots, templates or inventory data. No diagnostic is uploaded automatically. The merged application removes the Internet permission from OCR dependencies; their normal network-state permission remains declared.

## What is established about Core Filters

The audited APK defines 11 Core Filter variants. Lost Scrapyard's secret enemy **Spectrum** has eight variants in its loot table: black, purple, yellow, green, white, pink, orange and mint. This does **not** establish that each kill guarantees a filter, that all 11 variants can be farmed there, or that a particular boost maximizes filters/hour. Boost changes relic-drop thresholds and combat difficulty; no fastest loadout or rate has been measured.

Collection invokes the game's inventory-add path. A vanished enemy, item offer, filter label or completed run does not prove acquisition. Pilot reports **runs ended** and **Core Filter label screens**, never a fabricated earned-filter total. It does not read private save files or call Unity methods inside the game. Evidence and native offsets are in [docs/evidence](docs/evidence).

## Support boundaries

- Target: ARM64 Android phones, Android 11/API 30 or newer; compiled/targeted with API 35. Corebound package `com.Overcurve.Corebound`, version **1.2.1.1 / build 140**, English UI, landscape and gamepad/button input. Other installed game versions are rejected rather than silently assumed compatible.
- The supplied recorded replay tests cover a calibrated Crawler appearance. Different hulls, equipment, lighting and devices need their own phone validation and calibration. Phone throughput, screenshot support and OCR accuracy have not been measured here.
- Navigation is a **reactive visual patrol**. It does not reconstruct a run's random seed, guarantee exploration of every upper cavern, identify every Spectrum, verify every pickup or guarantee a sustainable farming rate. The exact 20-room library does not reveal a live run's room sequence, so the program does not pretend it does.
- Unknown menus, loading beyond a minute, changed foreground/display, poor tracking, unrecognized ads, three consecutive late captures, input/capture callback timeouts and exhausted recovery budgets pause or stop. Text recognition has a 15-second watchdog; if the SDK remains unresponsive, re-enable Pilot's accessibility service to recreate it. App termination does not auto-resume. There is no invasive game modification, root requirement, account access, automatic purchasing, ad-coordinate guessing, or updater.
- No phone/emulator was connected for a live target run during development. Automated and recorded-input success is not live farming success. This release candidate's remaining gate is a supervised physical-phone test of the full loop and actual inventory change.

## Build and check

Requirements: **JDK 17**, Android SDK **platform 35** and **Build-Tools 35.0.0**. Set `JAVA_HOME` and `ANDROID_HOME`; Gradle 8.9 is supplied through the checksum-pinned standard wrapper. Dependencies are pinned in the module build files. The original farmer is not a build dependency.

On Windows PowerShell, from this directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
```

The script also accepts `-Jdk <directory> -Sdk <directory>`. It can discover a JDK 17 and Android SDK in a sibling `.toolchain` directory; otherwise configure the environment or pass these options. Direct equivalent:

```text
gradlew.bat :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The installable development build is `app/build/outputs/apk/debug/app-debug.apk`. For a minimized release build:

```text
gradlew.bat :engine:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Gradle deliberately leaves that release APK unsigned. Sign it with your own Android release key using SDK `apksigner`, then verify it. The provided local release APK has a separate local signing identity; its private key is excluded from source/distribution archives. Keep its key backup if you intend to issue compatible updates; never publish it.

Keep release keys and password files outside the repository or under the ignored `.local/signing/` directory. They are deliberately absent from the source ZIP.

With JDK 17 and `ANDROID_HOME` set, the signing helper accepts an existing keystore and password file without putting the password on the command line:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\sign.ps1 -KeyStore path\to\release.jks -PasswordFile path\to\password.txt
```

It defaults to alias `pilot-release` (override with `-Alias`) and verifies the signature and 16 KB ZIP alignment. Key creation and custody remain with the distributor.

Optional development commands:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\format.ps1 -Check
.\gradlew.bat :engine:benchmark
python .\tools\package.py --apk path\to\signed.apk
python -m unittest discover -s tools -p test_package.py -v
```

The formatter is checksum-pinned. Packaging is tested with Python 3.12, checks archive contents, rejects unsafe output locations, excludes build caches/local credentials, and emits SHA-256 checksums. [GitHub Actions](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/workflows/check.yml) runs the same engine/Android checks plus formatting and packaging tests on pull requests and `main`. CI does not sign or upload a distributable APK.

## Tests and diagnostics

The pure Java engine tests exercise state confirmation, stale/duplicate observations, pending acknowledgments, retries, session/loading/unknown deadlines, external-app and orientation guards, ad/purchase boundaries, jump budgets, correlation matching, camera motion and actual recorded-image replay. Android Robolectric tests exercise configuration persistence/corruption/atomic writes, bounded snapshot validation, read-only diagnostic grants and simultaneous finite gesture construction. The benchmark reports **JVM replay timing**, not phone FPS or filters/hour. See [validation](docs/VALIDATION.md) for measured results and remaining checks.

If controls are disconnected, enable the accessibility service again. For repeated capture errors, check whether screen capture is restricted by the device, then disable/re-enable Pilot controls. If tracking or foreground probes fail, stop, capture a clear frame and recalibrate on the actual player and main floor. If menus/ads remain unknown, resolve them manually and resume; automatic repeated tapping is intentionally bounded. Corrupt settings can be reset with **Restore default settings**, while calibration can be reset separately.

The application does not depend on the earlier farmer's code, settings, signing identity, capture cache, mapper or server. Only the unmodified, standard Gradle wrapper tooling was copied; game facts were re-established from the copied APK and supplied recordings. No license or ownership grant is asserted by this project.
