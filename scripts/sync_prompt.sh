#!/usr/bin/env bash
# Copies shared/system-prompt.md into both clients, which can't read outside
# their own folders: the extension is loaded unpacked from chrome-extension/,
# and Android only packages what's under res/.
#
#   scripts/sync_prompt.sh          write the copies
#   scripts/sync_prompt.sh --check  fail if a copy is out of date (CI)

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
source="$root/shared/system-prompt.md"
js="$root/chrome-extension/lib/prompt.js"
raw="$root/android/app/src/main/res/raw/system_prompt.md"

render_js() {
	node -e '
		const text = require("fs").readFileSync(process.argv[1], "utf8");
		process.stdout.write(
			"// Generated from shared/system-prompt.md by scripts/sync_prompt.sh. Do not edit.\n\n" +
			"export const DEFAULT_SYSTEM_PROMPT = " + JSON.stringify(text) + ";\n"
		);
	' "$source"
}

if [[ "${1:-}" == "--check" ]]; then
	if ! diff -q <(render_js) "$js" > /dev/null || ! diff -q "$source" "$raw" > /dev/null; then
		echo "The prompt copies are out of date. Run scripts/sync_prompt.sh and commit." >&2
		exit 1
	fi

	echo "Prompt copies are up to date."
	exit 0
fi

mkdir -p "$(dirname "$js")" "$(dirname "$raw")"
render_js > "$js"
cp "$source" "$raw"
echo "Wrote $js and $raw"
