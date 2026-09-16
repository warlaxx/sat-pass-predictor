#!/usr/bin/env bash
# Downloads the Orekit data set (leap seconds, EOP, gravity models, planetary
# ephemerides). Large and updated regularly: never committed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${REPO_ROOT}/orekit-data"
ARCHIVE_URL="https://gitlab.orekit.org/orekit/orekit-data/-/archive/main/orekit-data-main.zip"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ "${1:-}" != "--force" && -d "$TARGET" ]]; then
  echo "orekit-data is already present in $TARGET (use --force to refresh)."
  exit 0
fi

echo "Downloading from $ARCHIVE_URL ..."
curl -fsSL -o "$TMP_DIR/orekit-data.zip" "$ARCHIVE_URL"

echo "Extracting ..."
unzip -q "$TMP_DIR/orekit-data.zip" -d "$TMP_DIR"

rm -rf "$TARGET"
mv "$TMP_DIR/orekit-data-main" "$TARGET"

echo "orekit-data installed in $TARGET"
ls "$TARGET" | head -5
