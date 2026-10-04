# 0.4.7 capture, navigation and sharing fixes

## Evidence and scope

Five phone map bundles were downloaded, byte-count/checksum verified and acknowledged before their phone copies were removed. The completed manual run retained 161 gameplay JPEGs and 161 terrain views. Its stored camera confidence was zero for 160 views; its existing union map cannot establish whole-level coverage. These private recordings are excluded from the repository.

The changed landmark sampling and matching accept 8 of those 161 sampled JPEGs, compared with 0 using the previous matcher in the same desktop replay. Most remain unregistered. This is a limited improvement on sampled images, not proof that the live capture stream can reconstruct the level; no image has been pasted into an atlas at an invented position.

The reported toolbar flashing came from the full-display capture path hiding it before each screenshot. Capture now masks its rectangle while keeping controls visible. Occluded cells stay unknown. Gameplay pixels no longer wait for the periodic full-text callback; the text reader uses the full original 1600-pixel view, avoiding a doubled-width/height allocation.

The controller evaluates active return sweeps and named-target returns before generic roof handling. Unregistered local driving retains the sweep. Low steps are distinguished from a rear enclosing wall, and downward-gap measurements are renewed at supported shaft ledges. Roof scouts have a lifetime bound that survives jump recharges; neither that bound nor contact with a roof proves coverage.

Map sharing is independent of the dashboard lifecycle, with foreground notification controls and a saved capability/port. Screen changes no longer own the listener. An unavailable saved port or a changed network address can require a new URL. Phone storage is deleted only after the existing authenticated, hash-matching receipt.

## Validation

- Android APK assembly and lint completed; no lint errors, 31 warnings.
- 178 JUnit/Robolectric checks passed. Added regressions cover unknown overlay cells, below-HUD gate counts, persistent return intent through roof contact/camera loss, bounded scouts across landing, saved server bookmarks, and dashboard closure/recreation with a real local HTTP server.
- Recorded vision corpus: 183 checks; complete-controller replay: 22 checks.
- Receiver integration: 6 scenarios; browser checksum: 5 vectors.
- Original signing certificate and 16 KiB native-page alignment verified locally.

The shaped-cavern regression completes two downward direction changes and traverses the final lower corridor. Robolectric lifecycle tests use a simulated Android runtime, and prerecorded views cannot react to injected inputs. No live phone trial or OCR-speed measurement was performed. Full ceiling coverage, every enemy's detection, changed ads and unattended farming remain unverified.

Package `com.corefilter.farmer`, version `0.4.7`, versionCode `11`. Original certificate SHA-256: `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`.

## Published artifact

The [GitHub release build](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/runs/37222850348) passed both receiver and Android release jobs at source commit `fa07dd40621c06881cf8d419965316eeb0c1056c`. [Version 0.4.7](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/releases/tag/v0.4.7) is public.

The APK was downloaded without account credentials and matched both its published checksum attachment and GitHub asset digest. Package/version, original certificate and 16 KiB page alignment were checked on that downloaded file. The canonical local copy is `dist/Ceiling-Scout.apk`.

- APK size: `52,207,734` bytes.
- APK SHA-256: `1561f12c800e53d79efc6eb5dfcd31d55ede430264d344a5f9bb5d5d6c935fc1`.
