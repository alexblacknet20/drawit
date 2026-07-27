# AGENTS.md — DrawIt

Android vector graphics editor. Single module (`:app`), Kotlin + Jetpack Compose.
AGP 8.2.2 · Kotlin 1.9.22 · Gradle 8.4 · minSdk 26 / target 34.

## Toolchain: fully self-contained, do not "fix"

- Everything lives in `.tooling/` (gitignored): JDK 17, Android SDK, Gradle home, downloads.
- `gradlew.bat` is **locally rewritten** (uncommitted change): it defaults `JAVA_HOME` to
  `.tooling\jdk` and `GRADLE_USER_HOME` to `.tooling\gradle-home`. No system Java/SDK or env
  vars needed. Do not restore the stock wrapper script.
- `local.properties` points `sdk.dir` at `.tooling/android-sdk`.
- `settings.gradle.kts` references a Termux path (`/data/data/com.termux/.../m2repo`) — kept
  for on-device ARM64 builds (aapt2 workaround). Harmless on desktop; builds verified working
  with it present. Leave it alone.
- `org.gradle.parallel=false` in `gradle.properties` is deliberate.

## Commands (Windows; use `gradlew.bat`, not `gradlew`)

```powershell
.\gradlew.bat assembleDebug        # build APK
.\gradlew.bat testDebugUnitTest    # all JVM unit tests (~1 min)
.\gradlew.bat testDebugUnitTest --tests "com.drawit.core.undo.UndoManagerTest"   # single class
.\gradlew.bat installDebug         # install to connected device
```

No lint/ktlint/CI configuration exists — `testDebugUnitTest` is the only automated gate.
Verified green as of this writing.

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
2. Undo = snapshot swap (`SnapshotCommand`) via `UndoManager` (bounded, `mergeWith` coalesces
   continuous drags into one step).
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
