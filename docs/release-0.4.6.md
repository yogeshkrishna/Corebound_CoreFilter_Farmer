Fix squeezed and sideways capture previews, including the shared image path used for navigation and mapping.

- Existing installs switch once to full-display capture with the floating bar briefly hidden. This avoids relying on transformed game-window layers during rotation.
- Preview waits for landscape and the launch transition to settle, then fits the image below its instructions without changing its proportions.
- A window buffer is never stretched into screen bounds. Rotated/scaled buffers trigger display-capture fallback; native window pixels retain their actual screen positions.
- Discard frames if display dimensions, rotation or foreground app changed during capture. Incorrect display-sized frames never reach OCR, steering or saved map images. Gameplay waits for landscape.

Update through Check for updates → Download & install → Install. Open Corebound in landscape and try Preview captured game again. Recalibrate if earlier calibration used a distorted image. Old map recordings may contain distorted observations; new manual recordings use the corrected capture path.

Build and focused geometry checks validate the repair; the result still needs confirmation on the phone.
