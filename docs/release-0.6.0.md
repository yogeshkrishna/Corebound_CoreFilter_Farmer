Record and build maps entirely on your Android phone, without a laptop or network connection.

- **Record a map** saves original-resolution, lossless Corebound PNGs and capture metadata on the phone. Stop finishes the accepted frame before closing the recording.
- **Recorded maps → Build / Resume** performs batch scenery matching, full-run revisit checks, camera-position refinement, and native tiled reconstruction. Progress, pause and cached resume work in the background.
- **View map & save PNG** offers a fitted pan/zoom preview and streaming original-resolution PNG export. Weak joins remain separate sections; unseen areas stay transparent.
- Original evidence can be shared as a ZIP. Recording checks remaining storage. Optional live laptop streaming and the farmer remain available.

Update through **Check for updates → Download & install**, or install `Ceiling-Scout.apk` over the existing app. Android requires installation confirmation. Do not uninstall or clear storage first.

**Use it:** enable controls → Use offline mapper → Record a map → allow screen capture → manually cover the whole level → Stop → Recorded maps → Build / Resume → View map & save PNG.

Preview reduction does not affect export resolution. Recordings remain private on the phone until shared; clearing storage or uninstalling removes them. Android/vendor background limits can pause long jobs; resume in the app. The APK supports ARM64 and x86_64 Android 11+.

Validation covers native pixel export, reversal/drop alignment, interruptions, Android UI/preferences and the actual packaged OpenCV mapper on an Android emulator. This does not establish perfect reconstruction of the real game or improve the farmer's navigation; a real phone run still needs checking.
