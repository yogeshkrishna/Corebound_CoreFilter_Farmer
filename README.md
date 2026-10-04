# Ceiling Scout

A personal Android farmer for **Corebound → Lost Scrapyard → Frozen ★5**, using the supplied Gilded Ember build. It reads the game on your phone, sends movement and jump taps, checks ceilings, pursues visible missed enemies, and repeats runs. It watches a reward ad when the displayed offer contains a recognized core filter.

**[Download the latest APK](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/latest)** · Android 11 or later

Version 0.4.4 adds a temporary **Map while I play** mode alongside the farmer. The mapper sends no automated touches: you play, scout ceilings and handle menus/ads. It saves the observed union map and sampled clean gameplay images with their poses. Press **Save** to keep a partial run; a recognized end screen saves a finished run automatically. Interrupted image recordings are recovered when controls reconnect. Farmer fixes let a drop leave its old waypoint through an observed lower passage, step past a platform edge wrongly classified as a wall, and align under a visible shaft beside a roof ledge. The overlay retains Run/Pause or Record/Save, Build, Calibrate and Stop; preview, capture settings and laptop transfer live in the app.

## Install this update

1. In your existing app use **Check for updates → Download & install**, then confirm **Install**. For a first installation, download **Ceiling-Scout.apk** from the latest release and open it with Android's Package Installer. **Do not uninstall first:** your build and calibration will stay saved.
2. Open Ceiling Scout. If controls are disconnected, use **Enable controls** and enable **Ceiling Scout controls** in Accessibility settings. For a sideloaded app Android may first require **App info → menu → Allow restricted settings**; wording varies by phone.
3. Tap **Show floating bar**, then **Open Corebound**. Select Lost Scrapyard with Frozen ★5. Starting inside gameplay assumes you selected that level yourself.
4. The small bar has **Run/Pause** (or **Record/Save** for manual mapping) and **•••**. Its menu contains Build, Calibrate and Stop & hide. Preview, capture settings and map transfer are in the app.
5. If needed, enter gameplay and choose **••• → Calibrate controls**. Tap the left button, right button, then jump area on the frozen image. These taps do not touch the game. Saved calibration turns off automatic movement-button location.
6. Press **Run** and supervise the first runs. Use **Edit build & farming settings** when your equipment changes.

The default is **three Magmatic ★7+ Hookshots: one base jump plus six extra jumps**. Hookshot count and any other extra jumps are editable. The app issues one press/release jump at a time, checks motion and overhead clearance before another impulse, and refills the budget after confirmed landing. The minimum jump interval defaults to 350 ms; actual impulses depend on fresh observations and trajectory clearance. Build notes and calibration remain saved.

## Updates from the phone

In Ceiling Scout use **Check for updates → Download & install**. On the first update, Android may ask you to allow installation from Ceiling Scout. Then confirm **Install** in Android's installer. You do not need to email each new APK to yourself.

Checks are manual. New builds must be published to GitHub Releases before the button can find them. Only a newer stable release is offered. The app checks its download hash, app identity, version code, and original signing certificate before installation. The configured public repository is editable. See [update and release instructions](docs/updates.md).

If updating from 0.2.0 after the repository rename, choose **Update source**, enter `yogeshkrishna/Corebound_CoreFilter_Farmer`, then **Save & check**. Version 0.3.0 keeps the current source automatically.

## Farming and floating controls

- **Run/Pause:** farming continues until stopped by default. Pause prevents new touches; an already issued batch releases within 700 ms.
- **••• → Build & Hookshots:** pauses farming and opens equipment notes, jump count, tap spacing, movement duration, contact time, run watchdog, and ad preference.
- **Calibrate controls:** saves the movement and jump touch positions.
- **Screen tools in the app:** preview freezes the captured game image; compatibility capture briefly hides the bar before display capture. Android 14+ normally captures the game window directly. Detected overlay contamination switches to compatibility capture.
- **Stop & hide:** stops farming and removes the bar. Reopen it from the home screen. Drag its status text to move it.

The navigator enters the corridor before selecting backward targets, jump-moves through visible areas, and uses additional height only for hidden roofs. Ceiling inspection is based on a clear view of its underside rather than proximity. Downward passages and real enclosing walls establish corridor turns; screen edges do not. Ember contact starts a burn attempt, with unresolved targets retained for a deliberate return sweep. A readable remaining-enemy count helps direct that sweep; a visible gate does not start an animation wait. See [controller details](docs/navigation.md).

## Saved maps on your laptop

This update temporarily offers **Map while I play** and **Use farmer** in the app. For manual mapping, select **Map while I play**, open Corebound, press **Record** on the bar, and play the route yourself, including high roofs and shafts. This mode sends no movement, jump, menu or ad touches. Handle rewards and Play yourself. Recognized run endings save automatically; **Save** also keeps an unfinished route. Switch to **Use farmer** to restore Run/Pause.

Manual bundles additionally retain clean gameplay JPEGs and measured poses, sampled at least 700 ms apart, up to 900 images or 32 MiB per recording. Interrupted staged recordings are recovered when controls reconnect; their lost in-memory atlas stays marked unknown. The images provide evidence for later reconstruction when terrain recognition or camera alignment fails. This update does not automatically replay a human route.

After runs, open **Saved maps & Wi-Fi transfer → Start Wi-Fi transfer** and keep that page open. Connect the laptop to the same Wi-Fi and open the displayed link. The laptop page provides a receiver for Windows and previews of saved maps. The receiver saves each ZIP, verifies its checksum, and acknowledges it before the app deletes that exact phone copy. Failed or interrupted transfers keep the phone files.

Each ZIP contains **map.png** and **map.json**: observed floor, ceiling and wall boundaries, the traversed path, ceiling inspection, enemy observations, coordinate units, confidence, build settings and the latest 1,200 steering decisions. Pausing an active run also saves its partial record. Unknown areas remain blank. Camera gaps that cannot be linked appear in separate panels. These records support future investigation of a fixed layout pool; the current controller does not assume one or reuse an unverified layout. See [transfer steps](docs/map-export.md).

Results are recognized before animation-speed taps. Continue and crate Close are followed by another verified Play on the selected target. A bare Play button cannot authorize a different level. Session/run-count caps are optional; per-run, capture, unknown-screen and stuck-recovery watchdogs remain active.

The reward strip, rather than a presumed Spectrum kill or main loot, determines whether to watch the optional ad. Recognized ad countdowns are allowed to finish before Close/Skip/X; each following end card is checked again. If an observed ad opens Google Play, the app returns with bounded Back attempts. Unrecognized ad stages or ambiguous rare-filter offers can require manual handling.

## Limits

The map is built from partial screen observations; the app does not know the whole level at launch. It cannot guarantee every high corner or enemy is reached. Enemy appearance does not reliably establish Spectrum or Dreadnought identity, and contact alone cannot prove a completed burn. Camera registration loss, different hulls, HUD colours, heavily occluded players, and changed ad creatives can need further tuning. Equipment notes do not automatically calculate damage or route timing. A cleared run is recorded separately from verified map coverage.

Recorded-frame checks validate recognition and decisions, not unattended farming success or live injected-input performance. See [current verification](docs/verification-0.4.4.md). Earlier [video findings](docs/video-analysis.md) and [research](docs/research.md) are retained as background.

## Privacy

OCR is bundled and runs on the phone. Farmer screenshots are processed in memory; manual mapping also saves bounded gameplay images in app-private storage. Maps, observations, build settings and a bounded decision log stay private. Maps and manual images are shared only through the local transfer you start; a verified receipt removes the transferred phone bundle. They are not uploaded to GitHub. Update requests and APK downloads use GitHub. Google's OCR SDK can also collect diagnostic device, app and performance metrics, as described in its [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). Installation uses Android's installer and its confirmation screen. No account login or GitHub token is stored in the Android app.

## Build and publish

JDK 17, Android SDK/build-tools 35, AGP 8.7.3 and Gradle 8.9 are pinned. On Windows, provision them with `tools/setup-toolchain.ps1` or use an existing installation, then:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
```

This assembles the debug APK, runs JUnit/Robolectric and Android lint, and checks the small recorded-frame corpus. The Gradle wrapper downloads the pinned distribution. GitHub Actions has a release workflow; [docs/updates.md](docs/updates.md) describes publishing and preserving the existing signing key.

This is a debug-signed personal build. Keep the original key for all updates; a new certificate cannot update an existing installation. Private signing material and original videos are excluded from the repository. No Play Store publication is included.
