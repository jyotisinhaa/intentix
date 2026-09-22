#!/usr/bin/env bash
# GAEE hazard scan — Stop hook.
#
# Greps only the Kotlin files changed since HEAD for the failure modes that
# actually hurt this app: a phone that goes silent, a leaked key, PII in logs.
# Advisory only — prints a summary and never blocks. Exit 0 always.

set -uo pipefail

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0

# Changed + untracked .kt files under the app's main sources.
mapfile -t files < <(
  {
    git diff --name-only HEAD -- 'gaee/app/src/main/**/*.kt'
    git ls-files --others --exclude-standard -- 'gaee/app/src/main/**/*.kt'
  } 2>/dev/null | sort -u
)

targets=()
for f in "${files[@]:-}"; do
  [ -n "$f" ] && [ -f "$f" ] && targets+=("$f")
done
[ ${#targets[@]} -eq 0 ] && exit 0

msg=""
scan() { # scan <label> <regex>
  local label="$1" pattern="$2" hits
  hits=$(grep -nE "$pattern" "${targets[@]}" 2>/dev/null | head -4)
  [ -n "$hits" ] && msg+="${label}"$'\n'"${hits}"$'\n\n'
}

# A failed ToolResult that says nothing — the user hears silence.
scan "SILENT FAILURE — failed ToolResult with no speakAfter:" \
     'ToolResult\( *false *, *"" *[,)]'

# Swallowed exception with an empty body.
scan "SWALLOWED EXCEPTION — empty catch block:" \
     'catch *\( *_?[A-Za-z]*: *[A-Za-z]*Exception *\) *\{ *\}'

# A hardcoded key, or the placeholder left in source.
scan "SECRET — key literal in source (use BuildConfig):" \
     'YOUR_CLAUDE_API_KEY|YOUR_OPENWEATHERMAP_API_KEY|sk-ant-[A-Za-z0-9]'

# Logging screen/message/contact data. Hand-check each hit.
scan "LOGGING — verify no PII, screen text, or key reaches logcat:" \
     'Log\.[dvwie]\(|printStackTrace\(\)'

[ -z "$msg" ] && exit 0

printf 'GAEE hazard scan (%d changed file(s)) — review before committing:\n\n%s' \
  "${#targets[@]}" "$msg" | jq -Rs '{systemMessage: .}'
exit 0
