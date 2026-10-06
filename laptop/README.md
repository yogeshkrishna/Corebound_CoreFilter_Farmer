# Ceiling Scout Studio

Extract the ZIP, then double-click **Start Studio.cmd** on Windows. The first launch downloads the image tools. Python 3.12 or newer must be installed; the developer's laptop can also use its bundled Python. Leave the app running while you play.

Connect the phone and laptop to the same Wi-Fi. Install Ceiling Scout 0.6.0 or newer, enable its controls, open **Optional · live laptop connection**, and scan the displayed QR or paste the connection link. Press **Start live capture**, accept Android's screen-sharing prompt, then play Corebound manually. The floating bar stops capture. Farming remains a separate choice.

The stable link pairs this phone with this laptop. It survives app switches and laptop restarts. The IP portion may change if your router gives the laptop a new address; copy the newly displayed link in that case. Keep the key private. This local service accepts only paired uploads, but uses HTTP on your trusted Wi-Fi.

Images are lossless PNGs at the phone's native landscape resolution, with the toolbar masked. The laptop retains every acknowledged image, capture time, device/orientation, mask rectangles, skipped upload count, SHA-256 checksum and registration result. This live-streaming mode does not save phone recordings. The separate offline mapper in Ceiling Scout 0.6.0 records and processes on the phone without Studio. Data lives in **Documents\Ceiling Scout Live**. Earlier Ceiling Scout map archives are never read.

The live map estimates translation from static features. It never stretches images. A join must have enough spatially distributed agreeing matches and no conflicting candidate. A failed join becomes a separate section; a menu or a frame without useful detail remains raw evidence. Unseen and masked areas stay transparent. Move through high ceilings manually and revisit gaps. Sparse, repetitive, obscured terrain can still defeat registration: native pixel scale does not guarantee a complete or perfectly registered level.

Use the section selector, pan/zoom, and **Save native PNG**. The PNG has one map pixel per captured screen pixel. **Save map data** contains observed rectangular edges, positions, capture/processing counters and confidence. Full per-frame metadata and original images are in the recording folder and SQLite database. The edges are evidence, not verified collision boundaries; decorations can produce false edges. Maps over 120 million pixels remain available as native 512-pixel tiles instead of silently reducing export resolution.

**New map** starts another recording and retains this one. Restarting the laptop app resumes the newest recording and rebuilds from original images. Uploads continue even when reconstruction falls behind; the pending count makes that visible. Upload acknowledgements are sent after the original file and metadata have been committed. Interrupted uploads are not acknowledged. Capture automatically retries new frames after a connection failure; it does not queue stale phone images during an outage.

For development: `python -m unittest discover -s laptop/tests -v` from the repository root. Start directly with `python laptop/studio.py --open`. To change storage, pass `--data PATH`; to change the fixed port, pass `--port NUMBER` and reconnect the phone with the new link.
