# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## Rules

- **No `android.*` imports in `core/`**, except `core/renderer/SkiaRenderer.kt`. Put Android-bound code in `canvas/`, `file/`, `text/`, `shapes/` or `tools/`. A hook in `.claude/settings.json` rejects violations.
- **Changing the saved document shape** (anything `DocumentSerializer` writes) means bumping `DocumentSerializer.FORMAT_VERSION`, and older files must still load. Use `/document-format-change`.
- **Kotlin files are formatted by ktlint 1.2.1 after every Write/Edit** (hook in `.claude/settings.json`, CLI at `~/.local/bin/ktlint`). Write code in that style (trailing commas, wrapped argument lists) so edits don't churn; ktlint can't auto-fix end-of-line comments on parameters (put them on the line above) or `_backing` properties for private properties.
- **Run `./gradlew testDebugUnitTest` before calling a change done.** If the toolchain is missing (JDK 17, Android SDK 34), say that tests were not run rather than claiming success.

## Undoable edits from tools

`EditorState.applyEdit(description) { doc -> newDoc }` is the only undoable path. Nothing overrides `Command.mergeWith`, so every `applyEdit` is its own undo step. For drags:
1. During the gesture, call `state.setDocumentForDrag(doc)`. It redraws but records no undo.
2. On `Up`, put the original shapes back with `setDocumentForDrag`, then make one `applyEdit` that goes from original to final.

`tools/select/SelectTool.kt` (Move/Resize/Rotate/Skew) follows this pattern.

## Git

Solo project: commit directly to `master` with descriptive, imperative commit messages. No branches or PRs.
