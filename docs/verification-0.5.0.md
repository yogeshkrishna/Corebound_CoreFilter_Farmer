# Verification: 0.5.0

This release replaces phone-side map archives with native live capture and an independent laptop reconstruction app. Farming's movement policy is retained; its removed mapping/archive adapter no longer dispatches simulated manual route decisions.

Android checks cover RGBA row padding, an unpadded final row, buffer offsets/pixel strides, truncated planes, private pairing validation, preference persistence, UI/build settings and run/reward/replay/camera-reset behavior. Android assembly and lint are required. Existing recorded-frame vision and navigation checks remain regression checks for farming, not sources for the new mapper.

Laptop integration checks upload through the HTTP endpoint, enforce authentication and native dimensions, preserve exact PNG bytes/checksums, accept duplicate retries idempotently, rebuild on restart and separate new recordings. Reconstruction checks reversed motion and vertical drops against known synthetic native imagery; every exported opaque RGB pixel must exactly equal the corresponding ground-truth pixel. Additional checks retain unknown alpha, reject menu views, separate unrelated scenes and keep native output at twice the analysis resolution. This tests a known translated scene, not Corebound's complete live terrain.

The browser viewer was exercised with isolated synthetic frames, explicitly labelled as validation data. Border toggle and original preview worked without browser errors. Export delivers original-scale RGBA PNG, not the zoomed browser canvas. Native pixels are selected by observation visibility; images are never brightened, averaged, stretched or warped. The original capture files remain available for reconstruction changes.

No live phone run has been captured with this release during development. Its actual throughput, motion registration, occlusion handling, full ceiling coverage and background/parallax behavior still require a phone trial. Sparse/repetitive scenery, moving lighting, decorations and camera changes can produce disconnected sections or registration error. Native scale and passing synthetic checks do not prove an exact full-level view.

The old desktop recordings were not used. Automatic approval review rejected their requested deletion; they remain in their previous folder, outside the new data store. Old private phone archive cleanup runs when the updated Android app is opened. No pairing keys, local IP configuration, map recordings or signing material are included in the public source or Studio package.
