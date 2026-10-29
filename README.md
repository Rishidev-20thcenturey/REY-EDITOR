# REY Editor

An original, offline-first Android image editor built from scratch for the REY project.

## Current prototype

- Native Android app shell with a dark editing workspace
- New white document (1200×800)
- PNG import through the Android document picker
- Canvas compositing with zoom display
- Brush drawing on raster layers
- Add/select layers
- Basic undo/redo snapshots
- PNG export to app-private external storage

This is an early prototype, not Photoshop parity. Masks, selections, groups, text, shapes, adjustments, filters, robust persistence, and production export are not implemented yet.

## Build

Android SDK 35, JDK 17, and Gradle 8.7+ are required. Local Android tooling is not bundled; GitHub Actions builds the APK.

```bash
gradle :app:assembleDebug
```

The project intentionally has no paid services and no copied proprietary assets or source.
