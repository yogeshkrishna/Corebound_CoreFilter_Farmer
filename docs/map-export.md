# Save level maps to your laptop

After a run ends, Ceiling Scout saves a ZIP in its private phone storage. The queue survives app restarts. Each ZIP contains **map.png**, **map.json**, and a short explanation.

The picture is a union of observed floors, ceilings, side walls, player paths, ceiling checks, and enemy sightings. It includes observations made near the spawn and after direction changes. Coordinates that could not be linked reliably are shown as separate panels. Blank areas are unknown. A successful run does not prove complete map coverage, and contact with an enemy is recorded as a burn attempt rather than a confirmed kill.

## Transfer over the same Wi-Fi

1. Connect your phone and laptop to the same Wi-Fi network.
2. Open Ceiling Scout and tap **Saved maps & Wi-Fi transfer**, then **Start Wi-Fi transfer**.
3. Copy the displayed link and open it in your laptop's browser. Keep this page open on your phone throughout the transfer. The phone keeps its screen awake while sharing.
4. On the laptop page, click **Download receiver**. Open PowerShell on your laptop and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File "$HOME\Downloads\receive-maps.ps1"
   ```

   This command runs that downloaded helper for this invocation. If your browser added a number to the filename, use its actual filename instead.

5. The receiver saves ZIPs in **Documents\Ceiling Scout Maps**, checks their file sizes and SHA-256 checksums, then sends a receipt for each verified file. Only that receipt removes the corresponding phone copy.
6. Tap **Stop Wi-Fi transfer** or close the phone's map page when finished. Leaving the app stops sharing. The stored queue is unaffected.

If you already have the repository helper, you can run it with the phone's current link:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\receive-maps.ps1 -BaseUrl 'http://PHONE-IP:PORT/CURRENT-TOKEN/'
```

Choose another destination with `-OutputDirectory 'C:\Your\Map Folder'`. The helper never overwrites an existing ZIP with different contents.

If the page cannot open, make sure both devices are on the same Wi-Fi. Guest Wi-Fi may prevent devices from reaching each other. Start sharing again to get a fresh link if the phone's address changed.

## Manual browser downloads

Click **Download ZIP** beside a map. Downloading or previewing never deletes a phone copy. To remove its phone copy, choose the ZIP you saved using **Verify a saved ZIP and remove its phone copy**. The browser checks the selected file's bytes and checksum, then asks you to confirm removal. Cancelling confirmation or selecting a different/incomplete file keeps the phone copy.

## What the data means

`map.json` includes your saved build and equipment notes; model confidence; camera and player positions; coordinate units and viewport aspect ratio; section bounds; observed occupancy cells; rectangular border segments; player path samples; ceiling inspection coverage; enemy sightings and burn attempts; the run outcome; and whether observation coverage was incomplete.

Version 0.4.2 also includes `screenTerrain` and matching `screenPoses`: up to 450 sampled local terrain views, including views taken while world registration was unavailable. A null camera offset means the view could not be placed reliably in the union picture. The raw observation is retained for later alignment instead of being discarded or assigned an invented world position.

X units are captured viewport widths. Y units are captured viewport heights and increase downward. The aspect ratio preserves their physical proportion in the picture. Unlinked sections have separate local origins; no connecting passage is invented. `complete` describes the navigator's coverage assessment, while `runSucceeded` describes the end-of-run outcome.

Bounded model/export limits are recorded in the JSON retention fields. The picture supports up to 16 section panels; additional retained geometry remains in JSON and is identified explicitly. Low-confidence boundaries use dashed lines. A blue dot marks the first observed spawn/path point in the first section and a local anchor in later unlinked sections. Orange dots mark observed horizontal path reversals, including backtracking toward enemies.

The server binds to the phone's Wi-Fi IPv4 address only and uses a new random sharing token each time. Files are never sent to a cloud service. Devices with the current sharing link can access the queue while sharing is open. SHA-256 verification prevents a partial or changed download from being acknowledged as the saved bundle. Interrupted transfers, wrong receipts, app restarts, and server shutdowns do not remove unacknowledged bundles.

## Temporary manual mapping mode (0.4.4)

Choose **Map while I play** in the app. Open Corebound, select the level and press **Record** on the bar before starting. Play normally, including the high ceilings and narrow shafts. The mapper does not steer, jump, tap results, close ads or return from the store. It waits through other screens. A recognized run-complete/death screen saves one map; you can start the next run yourself. Press **Save** on the bar to stop and save an unfinished route too. Return to **Use farmer** in the app for automation. These two modes are temporary data-collection tools, not the intended final interface.

Manual ZIPs also include **manual-frames/*.jpg** and **manual-frames/frames.jsonl**: clean game images sampled at least 700 ms apart, timestamps, measured player positions, map section, camera confidence and registered offsets. Unregistered offsets are null. Frames let later analysis inspect ceiling geometry that the current rectangle detector missed. They are not a video recording or labelled human button commands. The overlay displays the number of saved image samples. Raw images are limited to 900 samples or 32 MiB; a marker in the ZIP reports truncation while terrain mapping continues.

Images are written to private phone storage as play proceeds. On normal Save or completion they enter the verified-transfer ZIP, then their redundant staging copy is removed. If Android kills the service first, reconnecting controls packages the retained frames as **manual-recovered**. Its in-memory atlas was lost, so its map picture remains unknown; original image/pose evidence remains in the bundle. Nothing is claimed to be a complete floor/ceiling plan merely because you finished the level.

This update records evidence for a future navigation refinement. It does not yet identify a fixed map pool or automatically replay your route. Transfer is still started explicitly in the app over the same Wi-Fi. No phone map has arrived on the laptop until that transfer is completed.
