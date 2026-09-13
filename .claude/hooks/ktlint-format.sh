#!/usr/bin/env bash
# PostToolUse(Write|Edit): format the edited Kotlin file with the ktlint CLI.
# Keep the version in sync with ktlint { version } in app/build.gradle.kts.
# No-op if the CLI isn't installed; never blocks.
f=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
case "$f" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac
K="$HOME/.local/bin/ktlint"
[ -x "$K" ] || exit 0
"$K" -F "$f" >/dev/null 2>&1
exit 0
