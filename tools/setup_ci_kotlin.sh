#!/usr/bin/env bash
# Test-host compiler only; not an Android runtime dependency.
set -euo pipefail
DEST=${1:?Supply a new installation directory outside source}
[[ ! -e "$DEST" ]] || { echo "Refusing to overwrite compiler directory: $DEST" >&2; exit 1; }
mkdir -p "$DEST"
ZIP="$DEST/kotlin-compiler-2.2.10.zip"
curl --fail --show-error --location --retry 2 --proto '=https' --tlsv1.2 \
  https://github.com/JetBrains/kotlin/releases/download/v2.2.10/kotlin-compiler-2.2.10.zip -o "$ZIP"
# Official release checksum independently retrieved 2026-11-14; never trust a checksum from the same runtime download.
printf '%s  %s\n' 302d1d8e671e5c3207e6ed62ff11fb555462a628e22a1158254dcaaf7e7394bc "$ZIP" | sha256sum --check --strict -
unzip -q "$ZIP" -d "$DEST"
rm "$ZIP"
"$DEST/kotlinc/bin/kotlinc" -version
if [[ -n "${GITHUB_PATH:-}" ]]; then printf '%s\n' "$DEST/kotlinc/bin" >> "$GITHUB_PATH"; fi
