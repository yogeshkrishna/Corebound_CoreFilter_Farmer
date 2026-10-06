# Phone updates

Install the current Ceiling Scout APK over your existing app. Do not uninstall first: installing over it keeps your build settings and control calibration.

After that, open **Ceiling Scout → Check for updates**. The repository starts as `yogeshkrishna/Corebound_CoreFilter_Farmer`; it is editable if the project moves. A newer public release offers **Download & install**. The app downloads the APK itself, checks its SHA-256, and verifies that Android sees the same app, a newer version code and the original signing certificate. It then opens Android’s installer. Confirm **Update** to finish.

**Updating from 0.2.0 after the repository rename:** Check for updates may report that the attachment belongs to another repository. Choose **Update source**, enter `yogeshkrishna/Corebound_CoreFilter_Farmer`, and tap **Save & check**. This is a one-time change. Version 0.3.0 uses the current name and accepts a canonical same-owner GitHub release after a repository rename; APK signature checks remain required.

The first time, Android may ask you to allow Ceiling Scout to install apps. Choose **Open settings**, enable **Allow from this source**, and return to Ceiling Scout. It continues with the already downloaded APK. Installation approval is Android’s own screen; the farmer does not operate it.

Updates are checked when you press the button. There is no account login, scheduled update checking, embedded GitHub token or subscription. GitHub receives the release request and APK download; screenshots, calibration, build notes and farming logs stay on the phone.

Version 0.6.1 uses a smaller ARM64 phone APK (about 48 MB). A user-started download continues in a foreground service with a notification while you switch screens or lock the phone. The progress screen shows downloaded MB and transfer speed; pressing Check for updates reopens it. A silent connection times out after 12 seconds and reconnects from the saved byte offset, with at most four attempts per download action. Persistent errors offer Retry. Canceling or restarting keeps the partial file for the same release. Changing releases resets it; every completed APK still undergoes checksum and signing-key checks. If installation was postponed, a matching verified APK is reused.

**Stuck on an older version's download screen:** force-stop Ceiling Scout through Android App info. Open the latest GitHub release in the phone browser, download `Ceiling-Scout.apk`, and install over the existing app once. Do not uninstall or clear storage. The installed update enables the new downloader for future releases.

## Publishing the next version

Raise both `versionCode` and the three-part `versionName` in `app/build.gradle`. A GitHub release must have tag `v<versionName>`, one `Ceiling-Scout.apk` (the older `Ceiling-Scout-debug.apk` name is also accepted), and `Ceiling-Scout.apk.sha256`. GitHub’s asset SHA-256 is used when present; the checksum attachment provides a fallback.

The current personal app is debug-signed. **Every update must reuse the same original debug keystore**. A fresh machine or CI runner normally generates another key, which cannot update your existing app. Keep a private backup of the current `%USERPROFILE%\.android\debug.keystore`. Never add that file or its base64 form to this public repository.

The release workflow restores that original key from the GitHub Actions secret **ANDROID_DEBUG_KEYSTORE_BASE64** to a private temporary file and selects it explicitly through `SCOUT_SIGNING_KEYSTORE`. It fails if the secret is missing or the built APK's certificate differs from the original. Configure it once using GitHub CLI, sending the key directly through standard input:

```powershell
$signingBytes = [System.IO.File]::ReadAllBytes((Join-Path $env:USERPROFILE '.android\debug.keystore'))
$signingBase64 = [Convert]::ToBase64String($signingBytes)
$signingBase64 | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo yogeshkrishna/Corebound_CoreFilter_Farmer
Remove-Variable signingBytes, signingBase64
```

Do this only with the original key that signed the installed APK. The secret is not printed by this command. The workflow uses the standard debug alias and password, so a custom release key requires an explicit signing configuration and a planned migration.

Commit and push the reviewed source, then either push a matching version tag or run **Actions → Publish Android update → Run workflow**. The workflow builds, runs unit tests, recorded-frame checks and lint, verifies the original signing certificate, creates the checksum, and publishes a release. An already published tag is not overwritten. The corrected workflow passed its [first verified release run](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/runs/37136608628) on 3 October 2026; its downloaded APK matched the checksum and the original certificate.

For a local build and publication from the same machine/signing key:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\publish-release.ps1
```

This requires a clean, committed checkout and an authenticated GitHub CLI. Release notes can be provided with `-NotesFile <path>`. The script runs the local build checks before publishing. Native installation still needs a phone test; a successful desktop test does not prove every Android vendor’s installer flow.

Sources: [GitHub release API](https://docs.github.com/en/rest/releases/releases), [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [Android installer user-action settings](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
