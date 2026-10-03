# Verification record — 0.2.0

Validated on 3 October 2026 on Windows. The APK is a personal debug build signed with the original Android debug certificate, matching version 0.1.0.

## Reproduce

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
```

This runs APK assembly, JUnit/Robolectric, Android lint, and the recorded-frame checks.

## Results

| Check | Result |
|---|---|
| Java compilation and APK assembly | Passed |
| FarmEngine tests | 28 passed |
| Farming regression tests | 17 passed |
| ScreenInterpreter tests | 3 passed |
| Home/profile/layout tests, simulated Android API 35 | 6 passed |
| Android gesture construction tests | 4 passed |
| GitHub release and update identity policy tests | 11 passed |
| Installer callback/digest tests | 3 passed |
| Total JUnit/Robolectric | 72 passed; 0 failed; 0 errors; 0 skipped |
| Pixel/decision fixture checks | 120 passed, including real frames at three resolutions |
| Android lint | 0 errors, 23 warnings |
| APK signature | Verified; same signing certificate as the installed first build |
| Native library and ZIP page alignment | `zipalign -c -P 16 4` passed |
| Package/version | `com.corefilter.farmer`, versionCode 2, versionName 0.2.0 |
| Minimum/target Android API | 30 / 35 |
| Network/install permissions | INTERNET and REQUEST_INSTALL_PACKAGES; no ACCESS_NETWORK_STATE |
| CPU architectures | arm64-v8a, armeabi-v7a, x86, x86_64 |

The farming regressions cover all seven jumps across an ascent, confirmed ground recharge, ceiling sweep, targets behind/above the player, contact-and-burn traversal, gate backtracking, tier arrows attached to OCR digits, and results/crate/Play over 105 repeat cycles beyond the old run/session caps.

The Android gesture tests establish distinct press/release strokes alongside a movement hold and a 700 ms release bound. Home tests cover editable Hookshots, profile migration preserving calibration, invalid timing rejection, and a narrow portrait layout with explicit system-bar insets and 48 dp home action targets.

Updater checks cover stable semantic versions, checksum selection, repository/redirect validation, app identity/version/signature rejection, actual file hashing, forged callback rejection, and cancellation cleanup. They do not exercise Android's real package installation UI.

The small corpus retains the original reward, gameplay and menu fixtures plus six samples from the later phone trial. New checks cover split-platform feet, airborne frames, menu Play, missed hoverer candidates, and the centred result Continue. Controlled geometry also checks floor and ceiling contact. Enemy candidates do not establish Spectrum/Dreadnought identity or burn completion.

## Lint warnings

No broad suppression or baseline was added. Remaining categories: ClickableViewAccessibility (2), DataExtractionRules (1), DrawAllocation (2), RtlHardcoded (3), SetTextI18n (8), StaticFieldLeak (1), UnusedAttribute (1), UnusedResources (1), and UseSwitchCompatOrMaterialCode (4). Full generated reports are in `app/build/reports/`.

## Device limits

The user supplied a phone trial of version 0.1. No phone was connected for development of version 0.2, and a hardware-accelerated emulator was unavailable. Robolectric is a simulated Android runtime, not a phone test.

The updated overlay appearance on the iQOO display, clean-window/hidden-bar screenshot path, live ML Kit OCR, real multi-touch input, randomized ceiling coverage, ads, and native update installation need a phone trial. No fastest-route, unattended success-rate or filters-per-hour claim is established.

## Local APK

- File: `dist/Ceiling-Scout.apk`
- Size: 52,113,244 bytes
- SHA-256: `d3a11b2fb82fe50d128c06882900f355f5163d3ae9318cfc3dc238addafd96ed`
- Signing certificate SHA-256: `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`

GitHub publishes its own asset checksum alongside the APK. A CI rebuild may have a different file hash while retaining the same package, version and signing certificate.

Toolchain: Eclipse Temurin JDK 17.0.20.1, Gradle 8.9, AGP 8.7.3, Android compile SDK/build-tools 35/35.0.0, bundled ML Kit Latin text recognition 16.0.1, Robolectric 4.14.1.
