Fixes memory exhaustion while preparing map previews and reduces working memory throughout offline mapping.

- Process one map section at a time; release feature descriptors and camera-matching indexes before drawing native tiles. Resume reuses your existing saved recording, alignment and completed tiles.
- Full-resolution source images are read once during rendering. Visibility masks use narrow strips, and source-quality scores use bytes instead of full-frame float arrays. Original exported map pixels retain their native resolution.
- Previews are capped at 1024 pixels on the longest side, with smaller memory fallbacks. They decode tiles directly without retaining tile-quality caches. If every preview fallback fails, the completed native map still becomes available for PNG export.
- The viewer samples oversized older previews safely. Closing a viewer releases its bitmap; unavailable previews show an explanation while keeping Save native PNG available.
- Capture reuses its bitmap and a small strip buffer instead of allocating a full-frame pixel array each time. PNG recording uses buffered writes; optional laptop uploads stream PNGs from disk.
- Export assembles bounded temporary disk bands, avoiding repeated decoding of each tile for every output row. PNG filtering improves lossless compression. Sharing avoids recompressing already-compressed original images.
- Processing cannot overlap a previous job that is still releasing resources after a stop.
- Revisit matching retains the same ten candidates with one similarity calculation each. Eight-frame checkpoints and unchanged-tile detection reduce disk writes while preserving resume behavior.

**After updating:** open Recorded maps, choose your existing run, and tap **Build / Resume map**. No need to record again or delete your saved data. Use **View map & save PNG → Save native PNG** once complete.

Install through Check for updates, or install `Ceiling-Scout.apk` over the existing app. Do not uninstall or clear storage. Android 11+ ARM64. Actual timing and memory headroom still depend on the phone and recording; the preview is a display image, while native PNG export retains original map pixels.
