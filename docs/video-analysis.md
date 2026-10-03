# Analysis of the supplied runs

Two source videos were decoded locally with OpenCV. Their dimensions are 1042 × 480; durations are 84.20 and 63.70 seconds. The original videos stay in Downloads. Extracted evidence is in `analysis/`.

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

The second clip visibly reverses movement around the middle sectors and later moves right again. A universal all-right macro is therefore unsuitable. The controller uses gate location relative to the player, periodic jumps and bounded direction reversal after stalled progress. This remains a navigation heuristic, not a reconstructed map.

The user skipping the purple-filter ad is **not** treated as a behaviour to imitate. The user explicitly requires watching this offer. The supplied videos contain the offer but do not contain a complete ad or its close sequence.

## Implemented recognition

Pixel measurements recognize the golden gameplay HUD, golden crawler with blue details, closed red gates, paired movement-button outlines, yellow reward frame and round filter-like icons within pink rarity frames. Reward recognition is restricted to the lower-right offer, after OCR recognizes the Complete title. The main loot row cannot authorize an ad.

Purple, green and yellow icon shapes are directly supported by the supplied evidence. Additional colour rules cover mint/cyan, orange, pink and white, but those colours have not been validated against recordings from this phone. Ambiguous pink-framed offers pause for manual selection rather than discarding a potentially rare filter; black filters particularly need more examples. Occluded icons or changed game UI can still be missed.

There is no validated Spectrum enemy classifier. Repeated ceiling jumps let the existing weapons engage enemies, but they do not prove every high corner was reached or every Spectrum killed. Flight/dash hulls need different control logic; changing a build's notes does not invent that logic.

## Version 0.2 phone feedback

The later 177.47-second phone recording was sampled at twelve widely spaced times. No exhaustive frame-by-frame reconstruction was attempted. Six relevant samples are kept as regression fixtures under `analysis/v2/`; the original video and build photos are not published.

- At 0 seconds the correct level is selected, but version 0.1 has paused with a selection warning. The selected panel and Play geometry are visible. OCR tier arrows attached to the digit, such as `5t`, are now accepted. Pixel Play still requires the right target identity.
- The original build image contains three equipped Hookshots. The user clarifies that each Magmatic ★7+ Hookshot adds two jumps: seven total with the base jump. Version 0.2 issues separate jump taps in batches instead of one jump followed by a stationary ceiling wait.
- At 10 seconds the crawler is supported by a split platform; at 25 seconds it is airborne. Ground detection samples both feet and requires adjacent terrain rows. Navigation requires two ground observations after observed airtime before refilling the jump budget.
- Visible hoverers at 40 and 130 seconds become enemy candidates, including an overhead target at 130 seconds. The navigator can move back toward candidates and briefly recheck their last position. Appearance does not prove burning, a kill, or a Dreadnought identity.
- At 170 seconds the Complete screen has a centred Continue button, unlike the offset Continue beside an ad offer in the original run. The label's current coordinates drive the tap. Results, crate Close, and selected-level Play are tested together across 105 repeat cycles.

The smaller draggable bar has Run/Pause and a menu. A local captured-game preview and hidden-bar compatibility mode address the user's suspected overlay contamination. Recorded images cannot prove which screenshot API this phone uses or whether the overlay was the cause of the original single-jump behaviour.

## Verification

`tests/PixelVisionTest.java` checks real extracted frames at half, original and double resolution. It distinguishes rewarded filters from regular rewards, gameplay, inventory selection and the cooldown modal. The Java state-machine tests separately cover offered-filter selection, two-stage ad closing, countdowns, stale screens, package changes, gate waits and time limits.

These are recorded-frame and logic checks. Live OCR accuracy, Android touch injection, map coverage, ad SDK behaviour and achieved filters/hour require the game on the user's phone. No phone was connected during development.
