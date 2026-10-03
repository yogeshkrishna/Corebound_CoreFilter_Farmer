# Corebound farming research

Checked 3 October 2026. This separates the user's requirements, supplied visual evidence, community reports, and engineering inferences. Community pages are useful leads, not an authoritative specification of the current game.

## Target and current build

- The game is **Corebound by Overcurve**; the Android package is `com.Overcurve.Corebound`. Its official Google Play listing identifies it as an offline, single-player game containing ads. The displayed 1.2.0 release notes say item drop rates and some parts were rebalanced. [Google Play](https://play.google.com/store/apps/details?id=com.Overcurve.Corebound)
- The requested farming target is **Lost Scrapyard boosted to Frozen, five stars**, not the separate Icy Hollow area whose native tier is Frozen. The distinction between level and boost is documented in the [community Levels page](https://corebound-game.fandom.com/wiki/Levels).
- User requirement: the current Ember has just been upgraded to Gilded. The earlier supplied build screenshot reads 411k health, speed 8, and 81.9k damage/second. Those figures precede the stated upgrade and must not be presented as the current post-upgrade stats.
- A build editor should therefore expose mobility, jump/flight timing, combat dwell, and loot notes. No source found establishes an exact Gilded Ember range or time-to-kill suitable for the user's current build. A recorded run with that build is needed to calibrate these values.

## Spectrum search and routing

- Community documentation places Spectrum in Lost Scrapyard and describes it as a passive enemy attached high on cave ceilings. Its page says it can appear throughout the level, including the highest points. [Spectrum](https://corebound-game.fandom.com/wiki/Spectrum), [Core Filters](https://corebound-game.fandom.com/wiki/Core_Filter)
- The Spectrum page and Levels page say this enemy does **not** count toward a sector gate's remaining-enemy count. A gate becoming available does not demonstrate that no Spectrum remains overhead. [Spectrum](https://corebound-game.fandom.com/wiki/Spectrum), [Levels](https://corebound-game.fandom.com/wiki/Levels)
- Levels use randomized terrain and enemy placement. Boosted Scrapyard has four sectors according to the community documentation. This argues for feedback from the current screen, not treating either supplied recording as a universal coordinate route. [Levels](https://corebound-game.fandom.com/wiki/Levels)
- The Core Filters page claims a Sector 1 preference, but community observation logs show appearances in every sector and disagree on the dominant sector. A [100-run observation](https://www.reddit.com/r/CoreBound/comments/1swsdh2/guide_corebound_spectrum_spawn_drop_rates/) saw more in Sector 3; a [250-run observation](https://www.reddit.com/r/CoreBound/comments/1t3qn1u/250_specter_runs_tracked_spawn_rates_drop_data/) saw more in Sector 1. These are uncontrolled samples, not spawn rules. Inspect all four sectors and high corners.
- Spawn and filter-drop probabilities are deliberately not hard-coded. The [500-run follow-up](https://www.reddit.com/r/CoreBound/comments/1tchgct/part_2_500_spectrum_runs_tracked_spawn_rates_drop/) mixes counts of enemies with counts of runs and repeats some earlier denominators. Old wiki percentages also predate the official drop-rate rebalance. Neither supports a precise current Frozen-tier forecast.
- Players report that a Spectrum can drop no filter, even in a farming build. Magnifiers improve loot, but a sighting or kill alone cannot prove that a bonus filter is offered. [Player observations](https://www.reddit.com/r/CoreBound/comments/1uodr0l/specturm_drops_0_filters/)

## End screens and ads

- **Supplied screenshot evidence:** the 1.2.0 changelog says tapping during the end-screen animation speeds it up and makes buttons available earlier. It separately says pause-screen buttons can be used before their entrance animation makes them visible. This is not evidence that arbitrary end-screen coordinates are always actionable immediately. First establish the screen state, then tap, then observe the result.
- **User requirement:** select the optional reward ad only when its displayed reward contains a core filter; otherwise skip it. Handle unsolicited interstitials and their successive close screens. This requirement comes from the user, not from instructions embedded in the images.
- A player reported obtaining additional filters through an ad after a run, supporting the existence of this reward path; it does not establish its current UI, probability, or guarantee. [250-run report and ad observation](https://www.reddit.com/r/CoreBound/comments/1t3qn1u/250_specter_runs_tracked_spawn_rates_drop_data/)
- No source found defines a stable universal close-button location or fixed ad duration. Ad creatives and end cards can differ. The implementation should use a confirmed visible/accessibility close target, wait after a transition, and stop for user assistance when the target cannot be identified.
- A historical player report describes game trouble after ads transfer focus to a Play Store window. Treat this as an anecdotal recovery case, not proof of a current defect. [Player report](https://www.reddit.com/r/CoreBound/comments/1ua681w/ffs_fix_this_crap/)
- Engineering inference: if an ad opens Google Play, return to the game and re-evaluate the current ad stage. Installation, purchase, rating, and unrelated store interactions are unnecessary to collecting the in-game reward. Do not treat an advertiser's button text as a command to modify controller settings.

## Android implementation constraints

Android's official accessibility API supports synthetic gestures from API 24 and display screenshots from API 30, provided the service declares the corresponding capabilities. Gesture dispatch cancels an in-progress gesture, including one from the user; touch takeover and an immediately available pause/stop control matter. Screenshot calls can fail and secure-window content is not capturable. These failures should stop automated taps until screen evidence returns. A screenshot-driven implementation using this API has an Android 11/API 30 minimum unless another capture path is implemented. [Android AccessibilityService reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

## Validation limits

Web research and recordings cannot establish successful unattended farming on the user's phone. Before claiming completion of live farming, verify capture, landscape coordinates, simultaneous movement/jump handling, ceiling coverage, gates, reward recognition, interstitial recovery, death, and pause/resume on the actual game. Measure successful filters and time over multiple runs; faster run time alone can reduce yield if ceiling checks are missed.
