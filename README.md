# Ceiling Scout

Android farmer and live laptop mapper for **Corebound → Lost Scrapyard → Frozen ★5**.

**[Download the latest release](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/latest)** — `Ceiling-Scout.apk` for Android 11+, `Ceiling-Scout-Studio.zip` for the laptop.

## Offline mapping (0.6.0)

Record a full run, then build and export its map entirely on your Android phone. No Wi-Fi, laptop, account or network connection is needed for recording or processing.

1. Update through **Check for updates → Download & install**, then confirm Android's installer. Install over the existing app to retain build and calibration. You can also download `Ceiling-Scout.apk` from the latest release.
2. Enable **Ceiling Scout controls** in Android Accessibility if disconnected. Choose **Use offline mapper**, then **Record a map** and accept Android's screen-capture prompt.
3. Play Corebound manually. Cover every corridor, high ceiling, shaft and turn. The floating **Stop** button ends recording. You handle rewards and ads.
4. Return to Ceiling Scout → **Recorded maps · build, preview & export** → **Build / Resume map**. Stop capture first. Progress remains visible in the app and foreground notification while processing runs in the background. Pause and resume if needed.
5. Once complete, choose **View map & save PNG**, select a section, then **Save native PNG** and choose a destination. Export continues in the background; reopening Recorded maps shows its result. The display preview is fitted, but export retains original pixel dimensions.

Original PNGs, capture metadata, cached features, camera constraints/positions and native tiles stay in app-private storage. Delete this recording removes a selected run after confirmation; exported PNGs remain saved. Uninstalling or clearing app storage removes all private recordings. **Share original recording** prepares a ZIP containing this evidence for a destination you choose. Recording stops when less than 256 MB remains. Capture targets up to eight frames per second, skips while a PNG is being saved, and ignores other apps and portrait frames. Actual rate depends on your phone's encoding and storage speed.

Batch analysis first tracks adjacent views, then checks revisits against the whole recording and refines translation constraints. It never scales or rotates map pixels. Terrain is assembled in bounded 512 × 512 tiles; PNG export streams rows, avoiding a full-map bitmap. The preview may be reduced to 2048 pixels on its longest side. Weak or conflicting joins stay in separate sections. Missing observations remain transparent. Native scale does not guarantee full coverage or perfect registration, and extra processing cannot recover unseen ceilings. The real phone/game still needs a trial.

Android may interrupt long background jobs or the phone vendor may restrict them. Resume from Recorded maps; features, matching progress and completed tile frames are cached. Android 15 limits this foreground processing service to its daily time budget. The phone APK supports ARM64 Android 11+ devices. Native emulator tests include x86_64 with the explicit `-PscoutEmulator` build option.

## Optional live laptop mapping

Live streaming remains available under **Optional · live laptop connection**. Extract `Ceiling-Scout-Studio.zip`, open **Start Studio.cmd**, connect both devices to the same Wi-Fi, paste the Studio link under **Connect laptop**, and press **Start live capture**. Studio saves originals and reconstructs concurrently. Its link persists across restarts; a changed laptop IP needs reconnection. Python 3.12+ is required for first setup. See [Studio setup](laptop/README.md) and [capture protocol](docs/live-mapping.md). Existing laptop recordings are not imported into the offline mapper.

## Farming

Choose **Use farmer**, select Lost Scrapyard with Frozen ★5 in Corebound, then press **Run**. Enable **Ceiling Scout controls** in Android Accessibility if needed. Sideloaded apps may require **App info → menu → Allow restricted settings** first. The bar has Run/Pause and **•••**, with Build, Calibrate and Stop & hide in its menu. Drag the status text to move it.

The farmer jump-moves through visible corridors, uses air jumps for hidden roofs, attempts Ember contacts, revisits unresolved enemies, handles observed drops/turns, and repeats recognized runs. Core-filter reward ads are watched when the displayed offer is recognized. Countdown/close/end-card handling and bounded Play Store recovery are separate. Unknown screens can need manual intervention.

The supplied build has **three Magmatic ★7+ Hookshots: seven jumps**. Edit equipment and timings through **Edit build & farming settings**. Notes do not calculate damage. Calibrate once if automatic control location is wrong. Recorded maps are not yet used to steer farming. See [controller details](docs/navigation.md).

## Updates and development

Use **Check for updates → Download & install**. Downloads show MB and speed, reconnect with saved progress, and run in the background. Tap Check for updates again to reopen an active download. Android still requires installation confirmation. The app verifies checksum, identity, version and original signing certificate. If an older updater is stuck, force-stop the app and install the latest release APK over it once. See [release instructions](docs/updates.md).

`tools\setup-toolchain.ps1` prepares the Android toolchain; `tools\build.ps1` builds the APK and runs Android/recorded-vision/navigation checks. Install `laptop/requirements.txt`, then run `python -m unittest discover -s laptop/tests -v` for Studio. `python laptop/package.py --output dist/Ceiling-Scout-Studio.zip` packages laptop source without keys or recordings.

Tag releases verify Android, Studio and the original signer, then publish the APK, checksum and Studio ZIP. These checks validate specific capture, storage and reconstruction cases. Live capture and actual map coverage still need a phone trial; they do not establish error-free or unattended play.
