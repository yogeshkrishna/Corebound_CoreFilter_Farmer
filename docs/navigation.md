# Terrain and movement controller

Version 0.3.0 separates three decisions that the earlier farmer mixed together: what the screenshot shows, where those observations belong in the level, and which movement is currently useful.

`PixelVision` locates the crawler body, terrain contacts, candidate enemies and a 48 × 24 occupancy grid. HUD, touch controls, actors and bright attack effects are masked. A masked cell stays unknown; it is not assumed to be an empty passage. Pink enemy shapes and neutral bodies around magenta/cyan cores provide candidate observations, not a validated Spectrum or Dreadnought classification.

`TemporalVision` registers neutral terrain between screenshots and accumulates camera displacement. Particle flashes and independently moving actors cannot establish progress. Registration confidence and epochs accompany each frame. This matters when the crawler remains near the screen centre while the camera scrolls, and when screen Y appears to rise even though the crawler is falling relative to the floor.

`MapNavigator` keeps terrain, visited areas, ceiling sections and enemy tracks in world coordinates for the run. It plans through observed free cells with clearance around solid terrain. A target that scrolls off-screen remains unresolved. A touch starts a burn attempt; the planner waits for burn time and several clear local observations before retiring the track. Closed gates direct the search toward unfinished observations rather than a timed left/right sweep.

Movement remains a feedback controller: short steering commands, one jump at a time, and another observation before spending more charges. Floor contact after airtime recharges the configured budget. Ceiling contact suppresses jumps; a blocked command triggers retreat or a different waypoint. Uncertain observations and bounded recovery failures stop or wait instead of injecting an endless sequence of touches.

The Android service captures while a gesture runs. A one-slot mailbox keeps only the newest processed frame for the next decision, with generation and age checks. Cumulative camera coordinates keep skipped intermediate observations from losing displacement. OCR retains enough image resolution to read the selected star tier. Existing menu, reward-ad and package-provenance checks still govern taps outside gameplay.

The selected right-side menu panel is magnified for OCR, and its text coordinates are projected back to the full screenshot. Registration resets require distinctive terrain alignment; ambiguous views remain separately preserved. Jump reach adapts from observed rise and crest when camera registration is reliable. Boundaries of the HUD/control masks are excluded from exploration goals.

This is an incrementally observed map, not knowledge of the entire randomized level at launch. Occupancy errors, registration loss, moving targets and unobserved passages limit planning. Recorded replay and controlled geometry tests exercise failure cases; they do not simulate the game's complete physics or prove every enemy can be reached.
