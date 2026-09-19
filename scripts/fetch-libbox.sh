#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

VER="v1.14.1-lx.3"
AAR="libbox-${VER#v}.aar"
EXPECTED_SHA="bc9d313b040931323a5754500e62f5cbe8f5cb09503c44118eef3d0c8cbe83fd"
DEST="app/libs"
BASE_URL="https://github.com/Leadaxe/sing-box-lx/releases/download/$VER"

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Fetching $AAR from Leadaxe/sing-box-lx @ $VER"
curl -fsSL --retry 3 -o "$TMP/$AAR" "$BASE_URL/$AAR"

printf '%s  %s\n' "$EXPECTED_SHA" "$TMP/$AAR" | sha256sum -c -

mv "$TMP/$AAR" "$DEST/libbox.aar"
printf '%s' "$VER" > "$DEST/.libbox.version"

echo "Installed $DEST/libbox.aar"
