# Verification record — 0.3.0

Checked on 4 October 2026 on Windows. This personal Android build reuses the original signing certificate. The earlier release record is retained in [verification-0.2.0.md](verification-0.2.0.md).

## Reproduce

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
```

The script assembles the APK, runs JUnit/Robolectric and Android lint, then compiles and runs the independent recorded-frame and complete-controller replay checks.

## Scope of the checks

| Check | Result |
|---|---|
| APK assembly / Java compilation | Passed |
| JUnit and simulated Android checks | 101 passed; 0 failures, errors or skipped |
| Map / engine / selection regressions | 25 / 28 / 7 passed |
| Vision corpus at three resolutions | 183 checks passed |
| Recorded pixels through the complete controller | 20 checks passed |
| Android lint | 0 errors, 23 warnings |
| APK signature and original certificate | Verified |
| ZIP / native page alignment | `zipalign -c -P 16 4` passed |

- Registered world movement while the player remains stationary on screen; particle/HUD flashes cannot establish progress.
- Persistent ceiling coverage and off-screen targets; inaccessible targets do not starve the reachable frontier.
- Immediate roof clearance, mapped wall avoidance and recovery after failed horizontal movement.
- One jump per decision, seven-charge limit, two ground observations after airtime, capture-time velocity and learned jump rise.
- Body-sized route clearance, protection of known walls from player masking, and bounded low-confidence camera recovery.
- Verified terrain alignment after registration resets; otherwise old observations remain in an unlinked map section rather than being silently joined or deleted.
- Explicit sector completion clears ordinary resolved-room tracks; overhead candidates are retained conservatively because they may be non-gating Spectrums.
- Control/HUD mask boundaries cannot become exploration targets.
- Complete → crate Close → selected-level Play across 105 cycles; unreadable tier retry and rejection of explicit wrong tiers.
- Latest-frame mailbox replacement, stale/generation rejection, and rejection of delayed observations predating a jump's physical response.
- Saved build/calibration migration, Android gesture construction, update hash/package/signature policy and installer callback checks.

The recorded corpus adds the actual wall-contact frames, previously missed red-core/cyan ground bots, and manual landing sequence at three resolutions. The independent controller replay passes pixels through PixelVision, TemporalVision, ScreenInterpreter and FarmEngine: it avoids holding right into the recorded wall and identifies the recorded apparent upward screen motion as a world-space fall.

## Device limits

No phone is connected to this workspace. Robolectric is a simulated Android runtime, and prerecorded observations do not react to the newly proposed inputs. The tests therefore do not prove unattended completion, every enemy's detection, live OCR/touch performance, or a filters-per-hour improvement.

Burning light can hide enemy bodies. The terrain map is partial; uncertain camera resets can leave separate map sections that cannot yet be linked for backtracking. The controller waits, probes or pauses on uncertain observations instead of assuming a clear passage. Native installation and changed ad creatives still need verification on the phone.

## Release identity

- Package: `com.corefilter.farmer`
- Version: `0.3.0`, versionCode `3`
- Minimum / target API: `30` / `35`
- Certificate SHA-256: `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`
- GitHub source: `yogeshkrishna/Corebound_CoreFilter_Farmer`

The public repository was renamed after version 0.2. Existing 0.2 phones may need a one-time Update source change. Version 0.3 uses the current name and accepts a validated same-owner canonical release URL after a future rename. Downloaded APK identity and certificate checks remain required.

Toolchain: Temurin JDK 17.0.20.1, Gradle 8.9, AGP 8.7.3, Android SDK/build-tools 35/35.0.0, bundled ML Kit Latin recognition 16.0.1, Robolectric 4.14.1. Original videos and signing material remain excluded from the repository.
