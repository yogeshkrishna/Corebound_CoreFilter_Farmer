Ceiling Scout 0.3.0 replaces the old timed movement rules with a persistent terrain and enemy planner, based on the second phone trial and the manual Hookshot demonstration.

- Builds a local map from observed terrain and camera movement, keeping explored ceiling sections and enemy positions as they leave the screen.
- Routes through observed free space, changes height or retreats at blocked walls, and stops jumping when the ceiling is too close.
- Uses one jump at a time, checks ascent and clearance, and lands to recharge exhausted Hookshots. The default minimum interval is now 500 ms.
- Keeps missed enemies in memory and rechecks them after Ember burn time. Contact alone does not count as a kill.
- Captures the game during movement and uses the newest observation when a touch finishes, reducing the old capture–move–wait gaps.
- Keeps the existing interface, updates the jump timing setting, and preserves build notes and calibration.
- Improves small-text menu capture and retries an unreadable Frozen tier instead of immediately rejecting the selection. Explicitly wrong tiers remain blocked.

In the installed app, use **Check for updates → Download & install**, then confirm **Install**. Install over the existing app; do not uninstall first.

Validated with 101 app/policy tests, 183 vision checks and 20 recorded-controller checks; Android lint has no errors. These checks do not establish an unattended on-phone success rate or a fastest farming rate. The map is built from partial observations; unrecognized terrain, enemies or ad stages can still require intervention.
