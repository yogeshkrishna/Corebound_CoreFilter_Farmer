# 0.4.4 manual mapping and shaft exits

Inspected the supplied 10.6-second manual example at twenty evenly spaced instants. The user moves into the shaft, combines sideways movement with upward impulses through roof ledges, and climbs another narrow vertical section. The overlay is paused throughout: these movements are human evidence, not bot performance.

The farmer's drop waypoint could persist after entering a lower passage. A discretized platform side could also overlap the mapped hull and block stepping off. Fresh local passage evidence now takes precedence in that drop context. Lower exit selection requires observed descent, rather than reversing on the old platform. A visible clear vertical column beside a roof ledge permits lateral alignment while scouting.

Manual mapping has a separate observation-only path. It never enters navigation or ad/menu action handlers. The Android adapter also refuses gesture dispatch in manual mode. Observed registered terrain, borders, ceilings and paths are retained; uncertain offsets remain unlinked. Clean JPEG keyframes and measured poses are staged durably and packaged in saved ZIPs. Service startup recovers orphaned staged frames with explicitly unknown atlas coverage. Matching saved-file receipts remain required before deleting phone ZIPs.

Focused checks cover zero manual touches across gameplay, rewards, ads and store screens; saved run identity and partial runs; persistent mode selection; bundled image/pose integrity and interrupted-recording recovery. Existing shaped-cavern checks exercise two downward direction changes. These checks do not establish live phone navigation success or a complete recognized ceiling map.

Package: com.corefilter.farmer, version code 8, version 0.4.4. The original signing certificate is required for in-place updates.
