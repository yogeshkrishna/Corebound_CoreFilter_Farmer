Smaller phone updates and a downloader that recovers from interrupted connections.

- The ARM64 phone APK is about 48 MB, down from 117 MB in v0.6.0 (59% smaller). Emulator libraries are included only in explicit emulator test builds. Offline image processing remains on the phone.
- Interrupted or silent connections reconnect automatically, continuing from saved bytes. Retries are bounded; persistent problems show a Retry button. Canceling or restarting the app keeps progress for the same release.
- Downloaded MB and speed are shown. Checking for updates while one is running reopens progress. Switching screens or locking the phone is supported by a visible background download service.
- A complete verified download is reused if installation was postponed. SHA-256, app identity, newer version and original signing-key checks remain required before installation.

**If your current updater is stuck:** force-stop Ceiling Scout in Android App info, then download `Ceiling-Scout.apk` from this release in your browser and install it over the existing app. Do not uninstall or clear app storage. Android still asks you to confirm installation. Subsequent updates use the improved downloader.

Supports ARM64 Android 11+. This patch changes updating and APK packaging; it does not change mapper or farmer decisions. Download interruption/resume and activity/background recovery are tested locally; actual speeds depend on your connection and GitHub.
