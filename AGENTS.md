# AGENTS.md — DrawIt

Android vector graphics editor. Single module (`:app`), Kotlin + Jetpack Compose.
AGP 8.2.2 · Kotlin 1.9.22 · Gradle 8.4 · minSdk 26 / target 34.

## Toolchain (Linux)

- **JDK 17 is required** (matches `jvmTarget`). Gradle 8.4 / AGP 8.2.2 don't support running on
  newer JDKs such as a system JDK 25, so point `JAVA_HOME` at a JDK 17 when invoking Gradle.
- **Android SDK with platform 34**: set `sdk.dir` in `local.properties` (gitignored), or
  `ANDROID_HOME`.
- `settings.gradle.kts` references a Termux path (`/data/data/com.termux/.../m2repo`) — kept
  for on-device ARM64 builds (aapt2 workaround). Harmless on desktop. Leave it alone.
- `org.gradle.parallel=false` in `gradle.properties` is deliberate.

## Commands

```bash
./gradlew assembleDebug        # build APK
./gradlew testDebugUnitTest    # all JVM unit tests (~1 min)
./gradlew testDebugUnitTest --tests "com.drawit.core.undo.UndoManagerTest"   # single class
./gradlew installDebug         # install to connected device
```

No lint/ktlint/CI configuration exists — `testDebugUnitTest` is the only automated gate.

## Architecture (source: `app/src/main/kotlin/com/drawit/`)

Package root is `com.drawit`; applicationId is `com.drawit.app`. Single activity:
`MainActivity` (singleTask, also handles VIEW intents for `.drawit` files).

- `core/` — pure JVM logic, unit-tested on the host. **One exception:**
  `core/renderer/SkiaRenderer.kt` imports `android.graphics.*` (it's the adapter behind
  `IRenderer`). Keep all other `core/` files free of Android imports.
- `canvas/` — `CanvasView` (custom View) converts ALL touch/stylus/mouse/keyboard input into
  normalized `ToolEvent`s **in document coordinates**; tools never see Android events.
  `EditorState` holds document/viewport/selection/undo as Compose `mutableStateOf`; bump
  `documentVersion` / `viewportVersion` counters to trigger redraws.
- `tools/` — select, pen (freehand + `BezierPenTool`), shape, text, node editing.
- `file/` — `DrawItFile` (native ZIP format), SVG import, PNG/PDF export, image store.
- `text/` — `FontManager`, `TextEngine`. `shapes/BooleanOps.kt` uses `android.graphics.Path`
  (intentionally Android-bound, it is outside `core/`).

Key invariants:
1. Document model is immutable; edits produce a new `Document`.
2. Undo = snapshot swap (`SnapshotCommand`) via `UndoManager` (bounded to 100). No command
   overrides `mergeWith`; drags stay one undo step because tools preview with
   `EditorState.setDocumentForDrag` (no undo) and commit a single `applyEdit` on gesture end.
3. Rendering goes through `IRenderer` so the backend can be swapped later.

## Testing notes

- JUnit4 host tests only: `app/src/test/kotlin/` (geometry, document, undo, `canvas/
  EditorFeaturesTest`). There are no instrumented (`androidTest`) sources.
- `EditorFeaturesTest` instantiates `EditorState` on the JVM — this works because Compose
  runtime is JVM-safe, but any actual call into an Android framework stub (`android.net.Uri`,
  `android.graphics.Canvas`, ...) throws "not mocked". New JVM tests must stay on pure-Kotlin
  paths.

## Docs caveat

`README.md` describes the Phase 1 scaffold and lags reality: its file tree and roadmap
checkboxes are stale (file I/O, text, node editing, PDF/PNG export already exist). Trust the
code, not the README checkboxes.
