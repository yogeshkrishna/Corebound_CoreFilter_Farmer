The corridor controller replaces the previous flying grid planner. It enters the sector, jump-moves through visible caves, spends extra Hookshots only when upper roofs remain hidden, and handles downward direction changes through observed passages. Ceiling inspection uses visibility; missed enemies retain mapped positions for return sweeps. A visible gate no longer starts an animation timer.

Terrain/contact detection now requires rectangular boundary evidence, reducing false walls from decorative spikes. Camera tracking retains anchors through temporary occlusion and distinguishes an uncertain reanchor from a new sector. Labelled HUD enemy counts remain separate from Spectrum and ceiling coverage.

Each cleared or failed run saves a private map bundle containing a picture and raw geometry, path, coverage, enemy observations and build data. Use **Saved maps & Wi-Fi transfer** to download it on your laptop. The receiver verifies the saved checksum before the app deletes that phone copy. Layout-pool analysis and route reuse are reserved for a later iteration.

Update through **Check for updates → Download & install**, then confirm Android's installation screen. Existing calibration and build notes are preserved. The default minimum jump spacing becomes 350 ms; each jump still requires a fresh, suitable motion state.

Code and synthetic checks are documented in `docs/verification-0.4.0.md`. They do not establish unattended phone farming success, perfect perception or the highest possible farming rate.
