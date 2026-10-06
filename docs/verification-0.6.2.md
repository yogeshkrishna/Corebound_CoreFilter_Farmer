# Bounded-memory mapper verification

The consolidated local Android build passed all 209 unit tests, lint with zero errors, 183 recorded-vision checks and 22 navigation replay checks on 6 October 2026. A subsequent capture Canvas lifetime cleanup also passed assembly and lint.

The [native Android run](https://github.com/yogeshkrishna/Corebound_CoreFilter_Farmer/actions/runs/37417404074) passed against mapper commit `f8d94aa`. It exercises the packaged OpenCV and Android PNG implementations: exact strip-versus-full-image HSV exclusions across strip boundaries and occlusions; reversal/drop camera alignment; resume after an interruption; source-pixel equality in native PNG export; and feature/matcher release before preview preparation. The subsequent capture-only cleanup does not change this mapper code.

Focused checks also cover wide maps spanning more tiles than the cache, negative coordinates, transparent holes, visibility selection, file restart, persistent quality updates without PNG recompression, bounded display dimensions, cancellation, scratch cleanup, corrupt tiles, exact RGBA PNG filters/CRC, stable revisit-candidate ordering, native capture row padding/strides, original PNG dimensions and checksums, and native export access when a display preview is missing.

Memory bounds follow from the implementation, rather than phone-wide RAM measurements:

- Tile cache: four 512 × 512 RGBA/quality tiles in one active section, about 5 MiB total. Previously each retained section had an eight-tile cache, about 10 MiB per section.
- Display generation: at most 1024 × 1024 RGBA pixels (4 MiB), one sampled tile, no quality cache; falls back to 512 and 256. Previously the display bitmap alone could reach 16 MiB.
- Capture: one reusable native bitmap, two projection image slots, and at most 256 KiB of Java strip pixels. The former full-frame Java pixel copy and optional upload PNG copies are removed.
- Rendering: a single source bitmap, strip HSV work, byte masks/quality and the native distance transform. Full-frame duplicate BGR/HSV, Java source-pixel and float-quality buffers are removed. Analysis descriptors/indexes are released before rendering.
- Export: one decoded tile, native output-row buffers and at most 16 MiB of temporary disk band data; no full-map bitmap. A fixed scratch path is reused after process death and removed after normal completion/failure.

Registration thresholds, source-pixel selection, native output scale and existing analysis-v1/map-v1 data remain compatible. Checkpoints commit every eight frames and the final frame; interrupted work can replay at most seven immutable frames. Similarity scores are computed once per candidate and the exact original best-ten ordering is retained.

APK package `com.corefilter.farmer`, version `0.6.2`, code `15`, minimum Android 11, ARM64. Original signing certificate SHA-256: `65aa3b42d67d26d59344b24ecc0459a97b9d9f124a9961d980d76a36137f5097`.

No user phone or actual recording was accessible in this turn. These checks do not establish real-game registration accuracy, performance on every phone, or immunity to every possible memory limit. Unseen areas remain transparent; uncertain joins remain separate.
