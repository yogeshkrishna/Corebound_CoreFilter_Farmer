# 0.4.6 capture geometry repair

The supplied preview shows sideways game content stretched across the display, including an app-launch transition. Two code paths stretched images without respecting their geometry: window-buffer composition into accessibility window bounds, and preview rendering into the entire overlay. The first path also supplied OCR, navigation and manual image recording.

Capture now uses physical display dimensions and rotation, checks those values again at callback time, verifies the foreground package, and rejects mismatched buffers before analysis. Full-display compatibility capture is enabled once for existing installs. Optional native window capture uses pixel-for-pixel placement or falls back; it does not guess a rotation. Preview waits for landscape launch and fits the bitmap with its original proportions below the header. Portrait gameplay does not reach steering or map recording; portrait ads retain their existing handling.

Focused native-bitmap checks reject rotated/scaled buffers, verify a partial window's exact pixel placement and bounds, and preserve image proportions in a differently shaped preview. Android build, unit checks, lint and recorded-frame checks remain required for release. No live phone reproduction was performed; the user's next preview confirms device behavior.
