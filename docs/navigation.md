# Corridor controller, version 0.4.4

The controller commits to a direction through each cavern. Its normal action is forward movement with a base jump when grounded and when the measured trajectory clears the roof. It does not navigate an air grid or spend all Hookshots as a repeated jump batch.

Version 0.4.4 releases a shaft-entry waypoint when an observed descent reaches a supported lower exit. It can select the opposite passage without holding the old entry position. Fresh local passage evidence overrides a falsely protruding mapped platform corner while stepping into a drop. During a roof scout, a clear column beside a rectangular ledge permits a short sideways alignment before upward Hookshots resume. A continuous flat roof does not permit that probe.

Temporary manual mapping bypasses the controller entirely. It records terrain, camera measurements, path and ceiling visibility while the user supplies all touches. Unlinked views remain separate; sampled JPEGs are retained alongside the map for later inspection. It does not estimate the user's jump consumption or learn a replayable touch sequence.

## Decisions during a run

1. **Enter.** Move right into the first corridor. Rear observations remain recorded but cannot pull the crawler back into the spawn wall before it enters.
2. **Ground sweep.** Jump-move in the corridor direction, attempting Ember contact with bots along the path. A ceiling visible during normal traversal needs no dedicated climb. Its underside and hanging-enemy zone need two unobstructed observations to count as inspected.
3. **High-roof scout.** If normal movement still leaves an open region above the view, retain the scouting origin. Issue one additional Hookshot before the estimated crest, respecting the configured tap spacing and fresh observations. Check vertical clearance separately from the forward route; a nearby side wall permits a straight upward scout. Observe again before the next impulse. Stop climbing once the roof becomes visible, clearance is insufficient, or charges run out. Release at a ceiling instead of pressing jump against it.
4. **Return to ground.** Descend toward the scouting origin without spending extra jumps. On actual support, a blocking wall or a distance-based return deadline ends the return and resumes the sweep. Charge exhaustion never marks the unseen roof as verified. Retain discovered targets and unresolved ceiling columns, then complete the ground sweep and return to missed enemies.
5. **Descend and turn.** Follow a mapped floor opening. A lower landing together with an enclosing wall and free passage on the opposite side establishes a direction change. A screenshot edge, stalled action or ambiguous camera view cannot establish a turn.
6. **Revisit.** Keep off-screen targets at their observed world positions. Return toward a missed target, attempting contact through a ground jump or an overhead interception when necessary. Return time depends on distance and learned horizontal speed. At the exit, a fresh positive ordinary-enemy count starts a return sweep even if no enemy was detected. A moving sweep continues until the observed corridor start or rear wall; ten elapsed seconds cannot count as a completed sweep. A stalled return releases and pauses rather than claiming success.

In 0.4.2, unknown background above the crawler no longer suppresses a roof scout. A visible roof must supply a horizontal underside across the crawler's vicinity; one pillar cell does not establish a roof. A scout coasts for a second fresh roof view instead of immediately falling after its first glimpse. Roof contact still releases upward input immediately.

Sector-completed banners are included in asynchronous upper-half text reading. A new completion or a measured descent retains a turn candidate independently of floor registration. A closing wall in the old direction plus measured open space behind it permits a committed reversal. State is cleared together so the same banner cannot flip it twice. A lower landing by itself does not reverse an already corrected direction.

A visible gate does not start an animation timer. A zero count permits onward movement, while possible hanging enemies and uninspected roofs retain their own records. Ordinary enemy counts do not prove that a Spectrum was found.

## Measurement and action

`PixelVision` measures the hull, contacts, enemy candidates and a 48 × 24 terrain grid. Grey colour alone is insufficient for mapped rock: long rectangular faces and orthogonal edges supply geometry evidence. Internal texture holes, illumination and a tall wall clipped by the HUD have specific handling. Jagged strips are rejected. HUD, controls, actors and screenshot edges remain unknown.

Straight faces can also survive disconnected colour components when a perpendicular corner or a second parallel face supplies support. Measured brightness contrast handles a lit edge against dim grey background. Foot support allows the small gap between the detected golden hull and the visible platform; ceiling and side-contact gaps remain unchanged.

A clearly measured floor gives support evidence. Effect-obscured foot pixels give a weak candidate. Strong mapped support still requires stationary world-space foot evidence. Driving also accepts two fresh, stable local foot-support observations after the normal jump flight interval; this does not depend on camera registration and never inserts terrain or a fictitious path. If a base jump was ignored during the entry animation and the crawler remains supported, the controller retries it instead of permanently consuming a charge.

`TemporalVision` registers verified foreground landmarks, keeps camera displacement cumulative, and excludes moving actors and attack light. Temporary failures retain the old anchor. A sustained set of mutually coherent new views can establish an explicitly unlinked local origin; it does not invent a new sector or a connecting passage. Unregistered views cannot repaint terrain or append a fabricated world path.

`MapNavigator` learns horizontal speed, jump rise and gravity from suitable observations. It uses capture timestamps rather than delayed processing time for velocity. Each proposed jump checks a short ballistic trajectory with hull clearance against known solids. This is a bounded approximation of motion, not an exact Corebound physics model.

The service captures during gestures and retains only the newest processed observation. Gameplay pixels reach steering immediately; independent HUD OCR supplies timestamped enemy counts. Cached counts expire from the time their image was captured. Menus, rewards and ads still use full text recognition. A command uses one jump press/release at most, then another suitable observation. The default minimum impulse spacing is 350 ms; base jumps require local support, and air impulses are reserved for necessary scouts or overhead targets. Missing registration freezes world-map updates, not movement. Local driving checks visible walls and roofs, follows observed floor openings and retains the pending lower-cavern direction check until landing. A local scout returns over the ground traversed during its climb using a bounded command-duration estimate, not an invented world coordinate.

## Enemy and map records

A hull intersection starts a burn attempt. Off-screen disappearance does not establish a kill. A contacted track can retire after burn grace and multiple clear local absence views. A completed sector can clear ordinary tracks while retaining hanging candidates. Pixel appearance is not a validated Spectrum/Dreadnought classifier.

Terrain observations accumulate into an atlas. Adjacent floor, roof and wall segments merge into longer borders. Separate arrays retain player path, ceiling inspection and enemy history. A cleared, failed or manually paused run snapshots these records before resetting the navigator, and the service writes the map bundle asynchronously. The JSON also keeps the latest 1,200 decisions with support, camera confidence, jump budget, wall contacts and reasons. Unknown or unlinked areas stay explicit; clearing the run does not automatically set complete map coverage.

The current controller starts a fresh atlas each run. Stored bundles are for future layout comparison; the app does not yet identify a fixed layout pool or replay a previous route.

The JSON additionally retains up to 450 terrain grids sampled at least 700 ms apart, with capture times, camera confidence, player position and corridor direction. Missing camera offsets are null. These local observations survive camera gaps without being pasted into the world atlas at invented positions.

See [verification](verification-0.4.4.md) and [laptop transfer](map-export.md). Synthetic closed-loop tests exercise two downward direction changes and a forward sweep without unnecessary air impulses. These checks do not establish perfect perception, full real-level coverage, live farming speed or unattended success on a phone.

In 0.4.3, a camera-origin recovery retains an active scout or descent. Full-screen OCR periodically checks gameplay and always checks ads. Install-card evidence overrides false gameplay pixels. Two fresh confirmed Corebound views end an ad session. Pausing an ad preserves its state for resuming and bounded Play Store recovery. Overlay menus suspend new decisions and captures until dismissed; rotation clamps the bar and menu to the current display.
