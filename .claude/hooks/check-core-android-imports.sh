#!/usr/bin/env bash
# PostToolUse(Write|Edit): keep core/ free of Android framework references.
# Only core/renderer/SkiaRenderer.kt (the IRenderer adapter) may use android.*.
f=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
case "$f" in
  */app/src/main/kotlin/com/drawit/core/renderer/SkiaRenderer.kt) exit 0 ;;
  */app/src/main/kotlin/com/drawit/core/*.kt) ;;
  *) exit 0 ;;
esac
[ -f "$f" ] || exit 0
# Imports and fully-qualified uses (android.graphics.Path), ignoring comment lines.
hits=$(grep -nE '\bandroid\.[a-z]+\.' "$f" | grep -vE '^[0-9]+:\s*(//|\*|/\*)')
if [ -n "$hits" ]; then
  {
    echo "Blocked: core/ must stay free of Android imports (only core/renderer/SkiaRenderer.kt may use android.*)."
    echo "Move this code to canvas/, file/, text/, shapes/ or tools/, or put it behind an interface in core/:"
    echo "$hits"
  } >&2
  exit 2
fi
exit 0
