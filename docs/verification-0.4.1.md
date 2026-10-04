# 0.4.1 movement repair

The supplied failed 0.4.0 run showed a stationary crawler while the overlay reported an unregistered airborne view. Pixel inspection found a local foot-support candidate even though strong mapped support was false. The controller previously treated missing map registration as a reason to withhold movement or wait for support that itself required registration.

This version separates local driving from world mapping. Stable fresh foot support permits base jumping; rejected entry jumps retry after the normal flight interval. Missing registration cannot create mapped cells or world-path points. Hidden-roof impulses remain separate from base jumping, visible roofs stop upward input, and local drops retain a direction check until supported in the lower cavern. Gameplay HUD OCR runs independently of movement, and enemy-count freshness uses the original capture time.

Paused runs now retain their partial map and recent decision trace for Wi-Fi export. No private recording or screenshot is published.

The local APK build, controller regression checks, Android lint and existing recorded-frame checks passed. The APK is package `com.corefilter.farmer`, version code 5, version 0.4.1, signed with the original certificate `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`.

These checks verify code behavior and known observations; they do not constitute a successful phone farming trial. No guarantee of zero misses or maximum farming speed is made.
