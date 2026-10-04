# 0.4.2 ceiling and turn repair

The supplied 0.4.1 failure reaches Sector 2 and later pauses at an enclosing wall. Its controller could suppress scouting when overhead pixels were unknown and rely on floor registration for turning. Gameplay OCR also cropped out sector-completed banners below the HUD.

The repair uses explicit roof-underside evidence, retains an extra inspection view, reads sector banners, and shares a turn reset between registered and local driving. It also prevents a lower landing from blindly inverting a direction already corrected from the visible wall. Straight foreground-face recovery preserves component-fragmented rectangles; the foot-support gap accommodates the difference between measured golden hull bounds and the platform.

Sampled terrain grids survive camera gaps in exported JSON. Unknown offsets remain null rather than creating a fabricated connected map.

Regressions cover unknown overhead backgrounds, two turns without a registered floor, an airborne lower passage without a sector banner, and retained geometry without invented world offsets. Existing decorative-tooth rejection, known walls, camera disagreement, ceiling contacts, ads and repeat-run checks remain in the verification path. These checks do not prove unattended phone farming.

The final local APK build, unit checks, Android lint and recorded-observation checks passed. Package identity is `com.corefilter.farmer`, version code 6, version 0.4.2. The signing certificate remains `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`.
