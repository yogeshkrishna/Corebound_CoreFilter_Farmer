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
