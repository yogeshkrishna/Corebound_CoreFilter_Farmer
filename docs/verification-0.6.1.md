# Update recovery verification

Local Android build, unit tests and lint passed on 6 October 2026. All 193 unit tests passed, including 11 new download/recovery checks:

- Real HTTP socket interruption at 75% resumes at the exact saved byte offset; the final file matches the source.
- A silent stream times out and reconnects. Persistent disconnections stop after four attempts and retain the downloaded prefix.
- A new download invocation resumes a saved partial file. A server ignoring Range restarts safely; an incorrect Content-Range is rejected before writing.
- Cancellation preserves bytes for the next attempt.
- Returning after an activity replacement restores the progress dialog. Cancellation releases the busy state. Background completion waits for a visible activity.
- The foreground download service holds and releases its wake lock; the Android time-budget callback interrupts the operation with a recoverable error.

The 183 recorded-vision and 22 navigation replay checks also passed. The phone APK contains only ARM64 libraries. Its three ARM64 native binaries are byte-identical to v0.6.0; native mapping code is unchanged. Emulator CI explicitly includes x86_64 using `-PscoutEmulator`.

APK identity: `com.corefilter.farmer`, version `0.6.1`, versionCode `14`, minimum Android 11. APK signature verification passed with the original certificate SHA-256 `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`.

These checks reproduce network faults and Android lifecycle behavior locally. They do not measure the user's actual connection speed or prove every vendor's background/installer behavior. An already-installed older updater needs a direct APK installation to receive the fix.
