# Ceiling Scout

Android farmer and live laptop mapper for **Corebound → Lost Scrapyard → Frozen ★5**.

**[Download the latest release](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/latest)** — `Ceiling-Scout.apk` for Android 11+, `Ceiling-Scout-Studio.zip` for the laptop.

## Live mapping in 0.5.0

The phone-side mapper, saved-map queue, preview overlay and ZIP sharing server have been removed. The phone sends **native-resolution lossless PNGs directly to your paired laptop** using Android screen sharing. Ceiling Scout Studio saves the originals and reconstructs a map concurrently. Farming remains a separate choice.

1. On the laptop, extract `Ceiling-Scout-Studio.zip` and open **Start Studio.cmd**. First launch installs image tools. Python 3.12+ is required; this developer laptop can use its bundled Python.
2. Update the phone through **Check for updates → Download & install**, then confirm Android's installer. Install over the old app to retain your build and calibration.
3. Connect both devices to the same Wi-Fi. Choose **Live laptop map → Connect laptop** on the phone and paste Studio's link, or scan its QR with your phone camera.
4. Press **Start live capture**, accept Android's screen-sharing prompt, then play Corebound manually. Cover the floor, high roofs, shafts and turns. You handle enemies, rewards and ads.
5. Watch the map on the laptop. Pan/zoom, select separate sections when tracking is lost, and export **native PNG** or **map data**. **Stop** on the floating bar ends capture.

The link uses a persistent key and fixed port, so app switches do not replace it. A changed Wi-Fi IP requires the newly displayed link. The laptop app must stay running. Original PNGs, capture time, device/orientation, masks, skipped upload count, checksums and registration results stay under **Documents\Ceiling Scout Live**. Earlier archives are never imported. The update removes obsolete private phone recordings on first launch; it does not delete laptop folders.

Capture targets at most eight frames/second and skips frames while an upload is busy. Actual throughput depends on encoding and Wi-Fi. Other apps and portrait frames are skipped. Game menus/ads remain original evidence; reconstruction requires visible movement pads. The bar stays visible and is masked. Unknown areas stay transparent.

Reconstruction applies translation only. Registration may analyze a smaller image, but map tiles and exports retain the native pixel scale. Conflicting or weak matches are rejected; unlinked sections are **not** combined at guessed positions. Native scale does not guarantee full coverage or perfect registration. See [Studio setup and limits](laptop/README.md) and [capture protocol](docs/live-mapping.md).

## Farming

Choose **Use farmer**, select Lost Scrapyard with Frozen ★5 in Corebound, then press **Run**. Enable **Ceiling Scout controls** in Android Accessibility if needed. Sideloaded apps may require **App info → menu → Allow restricted settings** first. The bar has Run/Pause and **•••**, with Build, Calibrate and Stop & hide in its menu. Drag the status text to move it.

The farmer jump-moves through visible corridors, uses air jumps for hidden roofs, attempts Ember contacts, revisits unresolved enemies, handles observed drops/turns, and repeats recognized runs. Core-filter reward ads are watched when the displayed offer is recognized. Countdown/close/end-card handling and bounded Play Store recovery are separate. Unknown screens can need manual intervention.

The supplied build has **three Magmatic ★7+ Hookshots: seven jumps**. Edit equipment and timings through **Edit build & farming settings**. Notes do not calculate damage. Calibrate once if automatic control location is wrong. Laptop maps are not yet used to steer farming. See [controller details](docs/navigation.md).

## Updates and development

Use **Check for updates → Download & install**. Android still requires installation confirmation. The app verifies checksum, identity, version and original signing certificate. See [release instructions](docs/updates.md).

`tools\setup-toolchain.ps1` prepares the Android toolchain; `tools\build.ps1` builds the APK and runs Android/recorded-vision/navigation checks. Install `laptop/requirements.txt`, then run `python -m unittest discover -s laptop/tests -v` for Studio. `python laptop/package.py --output dist/Ceiling-Scout-Studio.zip` packages laptop source without keys or recordings.

Tag releases verify Android, Studio and the original signer, then publish the APK, checksum and Studio ZIP. These checks validate specific capture, storage and reconstruction cases. Live capture and actual map coverage still need a phone trial; they do not establish error-free or unattended play.
