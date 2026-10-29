# REY Editor architecture

## Goal

Build an original professional image editor around a pure document engine. The Android UI is a shell; editing rules must remain testable without Android.

## Boundaries

```text
Android Activity / file APIs
        ↓
thin bridge (future JNI)
        ↓
rey-core: document, commands, history, raster, masks, compositor, persistence
```

The current Java renderer is a temporary compatibility shell. New editing behavior belongs in `rust/rey-core` first, then the shell consumes it.

## Design rules

- Stable layer IDs, never UI row indexes as identity.
- Every user edit is a command with validation and undo semantics.
- Render is derived from document state; it is not the document model.
- Raster content is bounded by document dimensions and should move to tiles before large-document support.
- Masks and adjustments remain non-destructive data until explicitly rasterized.
- Project serialization is versioned and validated at the trust boundary.
- One brush gesture becomes one history entry.
- Never claim a performance target without a benchmark on the target device.

## Evidence basis

PhotoCraft's public architecture describes a pure-data document layer, a command registry, history integration, copy-on-write tiles, cached derived rendering, and a thin UI shell. REY uses these as independent engineering principles, not copied source or assets.
