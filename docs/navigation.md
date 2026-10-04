# Corridor controller, version 0.4.1

The controller commits to a direction through each cavern. Its normal action is forward movement with a base jump when grounded and when the measured trajectory clears the roof. It does not navigate an air grid or spend all Hookshots as a repeated jump batch.

## Decisions during a run

1. **Enter.** Move right into the first corridor. Rear observations remain recorded but cannot pull the crawler back into the spawn wall before it enters.
2. **Ground sweep.** Jump-move in the corridor direction, attempting Ember contact with bots along the path. A ceiling visible during normal traversal needs no dedicated climb. Its underside and hanging-enemy zone need two unobstructed observations to count as inspected.
3. **High-roof scout.** If normal movement still leaves an open region above the view, retain the scouting origin. Let the current jump rise, then issue one additional Hookshot when its ascent has subsided and overhead clearance permits it. Observe again before the next impulse. Stop climbing once the roof becomes visible, clearance is insufficient, or charges run out. Release at a ceiling instead of pressing jump against it.
4. **Return to ground.** Descend toward the scouting origin without spending extra jumps. Retain discovered targets and unresolved ceiling columns, then complete the ground sweep and return to missed enemies.
5. **Descend and turn.** Follow a mapped floor opening. A lower landing together with an enclosing wall and free passage on the opposite side establishes a direction change. A screenshot edge, stalled action or ambiguous camera view cannot establish a turn.
6. **Revisit.** Keep off-screen targets at their observed world positions. Return toward a missed target, attempting contact through a ground jump or an overhead interception when necessary. Return time depends on distance and learned horizontal speed. At the exit, a fresh positive ordinary-enemy count starts a return sweep even if no enemy was detected. A moving sweep continues until the observed corridor start or rear wall; ten elapsed seconds cannot count as a completed sweep. A stalled return releases and pauses rather than claiming success.

A visible gate does not start an animation timer. A zero count permits onward movement, while possible hanging enemies and uninspected roofs retain their own records. Ordinary enemy counts do not prove that a Spectrum was found.

## Measurement and action

`PixelVision` measures the hull, contacts, enemy candidates and a 48 × 24 terrain grid. Grey colour alone is insufficient for mapped rock: long rectangular faces and orthogonal edges supply geometry evidence. Internal texture holes, illumination and a tall wall clipped by the HUD have specific handling. Jagged strips are rejected. HUD, controls, actors and screenshot edges remain unknown.

A clearly measured floor gives support evidence. Effect-obscured foot pixels give a weak candidate. Strong mapped support still requires stationary world-space foot evidence. Driving also accepts two fresh, stable local foot-support observations after the normal jump flight interval; this does not depend on camera registration and never inserts terrain or a fictitious path. If a base jump was ignored during the entry animation and the crawler remains supported, the controller retries it instead of permanently consuming a charge.

`TemporalVision` registers verified foreground landmarks, keeps camera displacement cumulative, and excludes moving actors and attack light. Temporary failures retain the old anchor. A sustained set of mutually coherent new views can establish an explicitly unlinked local origin; it does not invent a new sector or a connecting passage. Unregistered views cannot repaint terrain or append a fabricated world path.

`MapNavigator` learns horizontal speed, jump rise and gravity from suitable observations. It uses capture timestamps rather than delayed processing time for velocity. Each proposed jump checks a short ballistic trajectory with hull clearance against known solids. This is a bounded approximation of motion, not an exact Corebound physics model.

The service captures during gestures and retains only the newest processed observation. Gameplay pixels reach steering immediately; independent HUD OCR supplies timestamped enemy counts. Cached counts expire from the time their image was captured. Menus, rewards and ads still use full text recognition. A command uses one jump press/release at most, then another suitable observation. The default minimum impulse spacing is 350 ms; base jumps require local support, and air impulses are reserved for necessary scouts or overhead targets. Missing registration freezes world-map updates, not movement. Local driving checks visible walls and roofs, follows observed floor openings and retains the pending lower-cavern direction check until landing. A local scout returns over the ground traversed during its climb using a bounded command-duration estimate, not an invented world coordinate.

## Enemy and map records

A hull intersection starts a burn attempt. Off-screen disappearance does not establish a kill. A contacted track can retire after burn grace and multiple clear local absence views. A completed sector can clear ordinary tracks while retaining hanging candidates. Pixel appearance is not a validated Spectrum/Dreadnought classifier.

Terrain observations accumulate into an atlas. Adjacent floor, roof and wall segments merge into longer borders. Separate arrays retain player path, ceiling inspection and enemy history. A cleared, failed or manually paused run snapshots these records before resetting the navigator, and the service writes the map bundle asynchronously. The JSON also keeps the latest 1,200 decisions with support, camera confidence, jump budget, wall contacts and reasons. Unknown or unlinked areas stay explicit; clearing the run does not automatically set complete map coverage.

The current controller starts a fresh atlas each run. Stored bundles are for future layout comparison; the app does not yet identify a fixed layout pool or replay a previous route.

See [verification](verification-0.4.1.md) and [laptop transfer](map-export.md). Synthetic closed-loop tests exercise two downward direction changes and a forward sweep without unnecessary air impulses. These checks do not establish perfect perception, full real-level coverage, live farming speed or unattended success on a phone.
