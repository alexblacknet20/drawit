---
name: document-format-change
description: Checklist for changing what gets saved in a .drawit file — adding/renaming/removing fields on Document, Page, Layer, Shape subclasses, Fill, TextShape, ImageShape, or ZIP resources. Use whenever a change touches core/document/ models or file/DocumentSerializer.kt / file/DrawItFile.kt.
---

# Changing the .drawit document format

The native format is a ZIP (`file/DrawItFile.kt`) holding `manifest.json`, `document.json` (written by `file/DocumentSerializer.kt`), `images/<id>.png`, `fonts/<file>` (only fonts whose name starts `imported:`) and `preview.png`. Serialization is hand-written `org.json`. There is no kotlinx.serialization and nothing is generated, so every field has to be written and read by hand.

## Checklist

1. **Model**: change the immutable data class in `core/document/`. Give new properties a default value so existing constructor calls still compile.
2. **Write**: add the field in the matching `toJson` / `put…` code in `DocumentSerializer`.
3. **Read, keeping old files working**: read the field with `optX("key", default)` / `optJSONObject(...)?.let { } ?: default`, never `getX`. The default must reproduce how older files behaved. `fromJson` treats a missing `formatVersion` as 1 and only rejects versions newer than `FORMAT_VERSION`, so every older version must still parse.
4. **Bump** `DocumentSerializer.FORMAT_VERSION` by 1. If an old value must be *interpreted differently* (not just defaulted), branch on the read `version` inside `fromJson`.
5. **Renames/removals**: keep reading the old key as a fallback (`optString("new", optString("old", default))`). Don't reuse an old key to mean something new.
6. **ZIP resources** (new images or fonts): update `DrawItFile` in both directions, and keep its existing checks: the 64 MB limit per resource and the safe-character check on IDs and file names.
7. **ProGuard**: `app/proguard-rules.pro` keeps `com.drawit.core.document.**`, `core.geometry.**` and `core.color.**` whole. A model class outside those packages needs its own keep rule, or release (minified) builds may break.
8. **Tests**: `org.json` comes from the Android framework, so a plain JVM unit test that calls `DocumentSerializer`/`DrawItFile` fails with "not mocked". Unit-test only the model logic in `core/document`. Check save/load round-trips on a device or emulator (`./gradlew installDebug`: save, reopen, and open a file saved before the change), and tell the user this manual check is still needed if you couldn't do it.
9. Run `./gradlew testDebugUnitTest`.
