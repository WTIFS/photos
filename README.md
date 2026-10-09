# Photos for Android

A native Android photo-library prototype with an Apple Photos-inspired library and viewer:

- Three-column library grid
- Tap to view and swipe between photos
- Bottom thumbnail filmstrip synchronized with the current photo
- Long-press multi-selection and system-confirmed deletion
- Full-screen viewing controls that hide on tap
- One-shot playback for Xiaomi `MVIMG_*.jpg` live photos

The app reads images from Android's `MediaStore`. Android owns the delete confirmation prompt, so photos are never deleted without the device user's confirmation.

## Build

Open this folder in Android Studio, allow it to install the Gradle version requested by the project, then run on the Xiaomi 17 Pro. The project targets Android 15 and requires Android 11 or later.

In **Tools > SDK Manager > SDK Platforms**, install **Android 15.0 (API 35)** before syncing. The Gradle wrapper is included, so a terminal build is then available through `./gradlew assembleDebug`.

On Android 14 and later, choose full library access in the system permission dialog for the complete library. Choosing selected-photo access limits the app to the selected images, as designed by Android.

Xiaomi live photos whose filenames begin with `MVIMG_` show a play button in the viewer. The app extracts the embedded MP4 segment to its cache, plays it once, and returns to the still image when playback completes.
