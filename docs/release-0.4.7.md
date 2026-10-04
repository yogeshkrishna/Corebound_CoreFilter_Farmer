Keep floating controls steady, retain missed-enemy returns, and keep Wi-Fi map sharing running in the background.

- Full-display capture no longer hides and restores the toolbar for every image. Its occupied area is excluded from OCR, actors, terrain and camera matching; covered terrain stays unknown. During an ad the bar moves away from close-button corners and returns afterward.
- Gameplay steering uses pixels immediately while text recognition reads banners, gate counts and ad evidence independently. Remove the capture-hide delay and extra post-gesture wait, reduce measurement image allocation, and target a 340 ms screenshot interval within Android's screenshot limits.
- A committed enemy return takes priority over generic ceiling handling. Camera gaps preserve its direction, and low steps can be jumped instead of prematurely ending the search. Labelled counts beside a visible gate can be read below the HUD.
- Remeasure a reachable downward gap at each supported shaft ledge instead of clinging to the original drop waypoint. Return-to-ground and drop actions take priority over generic roof contact.
- A roof scout cannot keep restarting indefinitely as intermediate ledges recharge jumps. Visible-roof inspection includes lateral movement or an inspection interval. Timeouts and spent jumps never mark unknown roofs as checked.
- Wi-Fi sharing is owned by a foreground service with a Stop notification. Closing its page, changing mode or opening the game leaves it running. Its random token and listening port are retained; restarting normally reuses the link. A changed Wi-Fi address or an unavailable port can still change the address.

Update in Ceiling Scout: **Check for updates → Download & install → Install**. Install over the old app to retain your settings. Start Wi-Fi transfer once in this version to obtain its new persistent link; the previous version's link is not carried across the update.

The app still uses partial visual observations. Your manual recordings remain private, and a completed run does not imply a complete map. Build, logic, recorded-image and sharing checks do not establish perfect real-phone navigation or faster filters per hour. Supervise the first updated run.
