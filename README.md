# DrawIt

Professional vector graphics editor for Android — CorelDRAW-class features with full touch, stylus, mouse, and keyboard support.

**License:** Apache 2.0 · **Status:** Phase 1 scaffold · **App ID:** `com.drawit.app`

---

## Architecture

Single-module app, organized by feature packages (chosen for AI-assisted solo development):

```
app/src/main/kotlin/com/drawit/
├── MainActivity.kt              # Entry point, Compose host
├── canvas/                      # The editor surface
│   ├── CanvasView.kt            # Custom View: renders doc, converts ALL input → ToolEvents
│   ├── CanvasScreen.kt          # Compose UI shell (toolbar, panels)
│   └── EditorState.kt           # Document, viewport, selection, undo — Compose-observable
├── core/                        # Pure logic, no Android deps (JVM-testable)
│   ├── geometry/                # Point, Rect, Matrix, PathData (SVG-compatible)
│   ├── document/                # Document → Page → Layer → Shape (immutable)
│   ├── undo/                    # Command pattern, coalescing, bounded stack
│   ├── color/                   # RGBA + CMYK conversion
│   ├── input/                   # ToolEvent (normalized input), Tool interface
│   └── renderer/                # IRenderer abstraction + SkiaRenderer (Phase 1)
└── tools/
    ├── select/                  # Pick/move, marquee, drag-with-undo-commit
    ├── pen/                     # Freehand with pressure + midpoint smoothing
    └── shape/                   # Rectangle/ellipse drag (Shift = square/circle)
```

### Key design rules

1. **Tools never see Android events.** `CanvasView` converts touch/stylus/mouse/keyboard
   into normalized `ToolEvent`s in *document coordinates*. Every tool automatically
   works with every input type.
2. **Document is immutable.** Every edit creates a new `Document`; undo = snapshot swap
   (delta commands come in Phase 2 for memory).
3. **Renderer is behind `IRenderer`.** Phase 1 = Skia via Android Canvas. When documents
   outgrow it, swap in a GL renderer without touching tools or document code.
4. **core/* has zero Android imports** — runs on JVM, fully unit-testable (see `app/src/test/`).

## Input support (Phase 1 scaffold)

| Input | Supported |
|---|---|
| Touch | 1-finger tool, 2-finger pan/pinch zoom |
| Stylus | pressure → stroke width, tool-type detection, palm-safe (finger events separate) |
| Mouse | left-drag tool actions, wheel pan, Ctrl+wheel zoom, Shift+wheel horizontal |
| Keyboard | Ctrl+Z/Y undo/redo, Delete, arrow-key nudge (Shift = ×10, Alt = ×0.1) |

## Build

Requires Android Studio Hedgehog+ / AGP 8.2, JDK 17, Android SDK 34.

```bash
./gradlew assembleDebug      # build APK
./gradlew testDebugUnitTest  # run JVM unit tests (core geometry/document/undo)
./gradlew installDebug       # install to connected device
```

Min SDK 26 (Android 8.0) · Target SDK 34

## Phase 1 roadmap (per plan)

- [x] Project scaffold, core geometry, document model, undo
- [x] Canvas with pan/zoom (touch + mouse + keyboard)
- [x] Select tool (tap, marquee, drag-move with undo)
- [x] Pen tool (pressure, smoothing)
- [x] Rectangle/ellipse tools
- [ ] Native file format (ZIP container) save/load
- [ ] SVG export
- [ ] PNG export
- [ ] Layers panel UI
- [ ] Artistic + paragraph text tool
- [ ] Fill/stroke property panel
- [ ] Snap-to-grid
- [ ] Node editing
- [ ] Gradient fills
- [ ] DXF export
- [ ] Ruler guides + snapping system

## Testing

Pure-JVM unit tests live in `app/src/test/kotlin/` — geometry, document,
and undo run on the host without an emulator:

```bash
./gradlew testDebugUnitTest
```
