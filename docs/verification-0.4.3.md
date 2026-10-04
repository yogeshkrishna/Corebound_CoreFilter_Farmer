# 0.4.3 high-roof and ad repair

A short inspection of the supplied 8:34 PM recording found the bot in a prolonged ground-return stage before manual control began, and an install-card ad incorrectly paused for uncertain player position. The later manual movements were not attributed to the bot.

Code review found that registered scouts required a forward ballistic route and a falling average velocity. Local wall handling could also take priority over an upward scout. The new scout uses vertical clearance and pre-crest timing, chooses no horizontal input at a blocking wall, and survives an unlinked camera-origin recovery. Supported ground returns have distance-based deadlines and stop pushing a blocking wall. Exhausting charges leaves roof coverage unverified.

Full OCR reads ads and periodically checks the entire gameplay screen. Ad text overrides false gameplay pixels; return to gameplay requires controls, player confidence and two fresh observations. Ad pause/resume keeps provenance for Play Store recovery. Unrelated external windows receive no touches. Overlay menus stop new decisions and captures, and rotation keeps their placement within the display.

Targeted regressions cover registered and local vertical wall scouts, camera-origin recovery, false gameplay on install cards, two-view ad return and paused ad provenance. Existing terrain, turn, reward, repeat-run and map-export checks remain required. These checks do not prove live timing, perfect perception or unattended farming success.

Package identity remains com.corefilter.farmer; version code 7, version 0.4.3. Release signing must retain the original certificate.

The local APK build, unit checks, Android lint and existing recorded-observation checks passed. The final overlay scrolling adjustment also passed the APK build and lint. The release workflow repeats the required checks against the published commit.

Recognized menu/ad taps underneath the floating bar temporarily hide the bar so the touch reaches the underlying control.
