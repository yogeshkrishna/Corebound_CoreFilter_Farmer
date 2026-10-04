# Analysis of the supplied runs

The two original runs were decoded locally with OpenCV. Their dimensions are 1042 × 480; durations are 84.20 and 63.70 seconds. Later phone feedback and Hookshot evidence are recorded below. Original videos stay in Downloads; extracted local evidence is in `analysis/`.

## What was observed

| Observation | Evidence |
|---|---|
| Target is Lost Scrapyard, boosted to Frozen ★5 | Both opening level-selection screens |
| Left/right movement at approximately (0.166, 0.81), (0.282, 0.81) | Both videos' lower-left buttons |
| Right-half touch is a jump tap | Explicit user clarification; not inferred from the touch marker alone |
| First run finishes in 75.32 seconds | `analysis/clip1_81.5.png` |
| Second run finishes in 52.09 seconds | `analysis/clip2_56.5.png` |
| Optional ad reward contains a purple core filter | Second clip at 56.5–57 seconds, lower-right strip |
| Main loot and optional ad rewards occupy different regions | Second clip at 56.5 seconds |
| First run has an optional ad reward without a detected core filter | First clip at 81.5 seconds |
| Crate cooldown Close is at approximately (0.520, 0.823) | First clip 83 seconds; second clip 58 seconds |

The second observed run is 30.8% shorter. Two different randomized layouts do not establish whether gilding caused that improvement or whether either run is optimal. The build photo's 411k health, speed 8 and 81.9k DPS predate the user's stated Ember gilding.

The second clip visibly reverses movement around the middle sectors and later moves right again. A universal all-right macro is therefore unsuitable. Versions 0.1–0.2 used gate-relative movement, periodic jumps and bounded direction reversal after stalled progress. **Those movement rules are historical and are replaced in 0.3** by terrain mapping, persistent targets and observed single-jump decisions.

The user skipping the purple-filter ad is **not** treated as a behaviour to imitate. The user explicitly requires watching this offer. The supplied videos contain the offer but do not contain a complete ad or its close sequence.

## Implemented recognition

Pixel measurements recognize the golden gameplay HUD, golden crawler with blue details, closed red gates, paired movement-button outlines, yellow reward frame and round filter-like icons within pink rarity frames. Reward recognition is restricted to the lower-right offer, after OCR recognizes the Complete title. The main loot row cannot authorize an ad.

Purple, green and yellow icon shapes are directly supported by the supplied evidence. Additional colour rules cover mint/cyan, orange, pink and white, but those colours have not been validated against recordings from this phone. Ambiguous pink-framed offers pause for manual selection rather than discarding a potentially rare filter; black filters particularly need more examples. Occluded icons or changed game UI can still be missed.

There is no validated Spectrum enemy classifier. The earlier repeated-ceiling-jump strategy did not prove every high corner was reached or every Spectrum killed; the later recording shows why it needed replacing. Flight/dash hulls need different control logic; changing a build's notes does not invent that logic.

## Earlier phone feedback: changes made in 0.2

The later 177.47-second phone recording was sampled at twelve widely spaced times. No exhaustive frame-by-frame reconstruction was attempted. Six relevant samples are kept as regression fixtures under `analysis/v2/`; the original video and build photos are not published.

- At 0 seconds the correct level is selected, but version 0.1 has paused with a selection warning. The selected panel and Play geometry are visible. OCR tier arrows attached to the digit, such as `5t`, are now accepted. Pixel Play still requires the right target identity.
- The original build image contains three equipped Hookshots. The user clarifies that each Magmatic ★7+ Hookshot adds two jumps: seven total with the base jump. Version 0.2 issued separate jump taps in batches instead of one jump followed by a stationary ceiling wait. This batch strategy is also replaced in 0.3.
- At 10 seconds the crawler is supported by a split platform; at 25 seconds it is airborne. Ground detection samples both feet and requires adjacent terrain rows. Navigation requires two ground observations after observed airtime before refilling the jump budget.
- Visible hoverers at 40 and 130 seconds become enemy candidates, including an overhead target at 130 seconds. The navigator can move back toward candidates and briefly recheck their last position. Appearance does not prove burning, a kill, or a Dreadnought identity.
- At 170 seconds the Complete screen has a centred Continue button, unlike the offset Continue beside an ad offer in the original run. The label's current coordinates drive the tap. Results, crate Close, and selected-level Play are tested together across 105 repeat cycles.

The smaller draggable bar has Run/Pause and a menu. A local captured-game preview and hidden-bar compatibility mode address the user's suspected overlay contamination. Recorded images cannot prove which screenshot API this phone uses or whether the overlay was the cause of the original single-jump behaviour.

## Version 0.2 failures and manual Hookshot evidence

The latest farmer recording (`10.19.05 PM`) lasts 266.74 seconds; the manual Hookshot recording (`10.21.48 PM`) lasts 36.96 seconds. Both are 1042 × 480. Timestamped contact sheets, targeted movement sequences and touch-marker measurements are stored locally under `analysis/v3/`. Times below refer to the video file, not the in-game timer.

| Video / time | Visible evidence and the failure it exposes |
|---|---|
| Farmer, 0 and 256 s | Correct target menu is visible alongside a selection warning or “screen not recognized”; menu recognition remains unreliable independently of movement. |
| Farmer, 12–15 s | Pink flying and gray/purple ground bodies are visible; the controller alternates ascent and return/check messages without a stable room plan. Pink colour alone is insufficient enemy coverage. |
| Farmer, 20–42 s | A closed gate triggers repeated backtracking, gate checks and ascent instead of a persistent search for unfinished targets. |
| Farmer, 54–64 s | The crawler repeatedly contacts the same tall right wall while advance/jump actions continue. Human pausing and left movement around 60–66 s frees it. |
| Farmer, 120–153 s | Repeated traversal of exposed left-side geometry and another wall sequence consume time while the right-hand room remains unfinished. |
| Farmer, 155–176 s | Human control advances right; two gray/cyan ground crawlers remain visible ahead at 168 s. Contact is followed by Sector 3/4 completion at 176 s, demonstrating missed required enemies. |
| Manual, 8.5–9.0 s | The player's screen Y rises while it actually falls: the floor scrolls upward into view before landing. Screen Y alone cannot determine ascent or jump recharge. |

The first seven distinct manual jump-marker starts are **3.581, 4.141, 4.662, 5.142, 5.658, 6.540 and 7.488 seconds**. Their spacing is approximately 480–950 ms, with visible coasting and falling between impulses, compared with 0.2's 190 ms default batch spacing. The first ascent begins around 3.65 s, crests around 3.9 s and falls by 4.1 s; camera movement prevents deriving a reliable gravity or jump-height constant from those screen positions.

An eighth marker at 8.355 s shows no fresh upward motion relative to terrain before landing around 9.0 s. A new jump at 9.334 s after landing works. This is consistent with the supplied seven-jump build and ground recharge; no charge counter was read from the recording. Visible marker duration includes persistence/fading and is not a measurement of Android touch duration. The tether alone does not establish grounded state.

Version 0.3 replaces the earlier timer-driven batches and direction reversals with a partial terrain atlas, camera-corrected positions, remembered enemy candidates, reachable exploration goals and one jump followed by motion observation. Ceiling clearance suppresses further ascent; wall handling chooses a retreat or another route. Unobserved space remains unfinished rather than being assumed clear. These changes address the recorded decision failures; they do not establish complete Spectrum coverage or optimal speed.

Useful local replay evidence includes `manual_landing.jpg` and its 7.5–10.25 s frames, `farmer_wall.jpg`, `farmer_room3.jpg`, and `farmer_15.00.png` / `farmer_168.00.png` for differently coloured enemy bodies. Original recordings are not published. The farmer clip contains multiple human interventions and does **not** demonstrate an unattended successful complete run.

## Verification

`tests/PixelVisionTest.java` checks real extracted frames at half, original and double resolution. It distinguishes rewarded filters from regular rewards, gameplay, inventory selection and the cooldown modal. The Java state-machine tests separately cover offered-filter selection, two-stage ad closing, countdowns, stale screens, package changes, gate waits and time limits.

These are recorded-frame and logic checks. Live OCR accuracy, Android touch injection, map coverage, ad SDK behaviour and achieved filters/hour require the game on the user's phone. No phone was connected during development.
