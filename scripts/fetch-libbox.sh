#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

VER="v1.14.1-lx.8"
AAR="libbox-${VER#v}.aar"
EXPECTED_SHA="beab998c9d0a46826db0d654b5315fa6a25d9e1cbf8cb10c547e757444c26d80"
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
