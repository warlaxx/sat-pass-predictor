#!/usr/bin/env bash
# Telecharge le jeu de donnees Orekit (sauts de seconde, EOP, modeles de gravite,
# ephemerides planetaires). Volumineux et mis a jour regulierement : jamais commite.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${REPO_ROOT}/orekit-data"
ARCHIVE_URL="https://gitlab.orekit.org/orekit/orekit-data/-/archive/main/orekit-data-main.zip"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ "${1:-}" != "--force" && -d "$TARGET" ]]; then
  echo "orekit-data deja present dans $TARGET (utilise --force pour rafraichir)."
  exit 0
fi

echo "Telechargement depuis $ARCHIVE_URL ..."
curl -fsSL -o "$TMP_DIR/orekit-data.zip" "$ARCHIVE_URL"

echo "Extraction ..."
unzip -q "$TMP_DIR/orekit-data.zip" -d "$TMP_DIR"

rm -rf "$TARGET"
mv "$TMP_DIR/orekit-data-main" "$TARGET"

echo "orekit-data installe dans $TARGET"
ls "$TARGET" | head -5
