#!/usr/bin/env bash
# Amazon's hls.js for Vega: hls.js 1.5.11 with Amazon's patches (fetch in place of XHR,
# array buffers, the W3C media player's quirks), its player wrapper and the polyfills it
# needs, built into src/hls. Amazon's files are under Amazon's own licence and aren't kept
# in this repository: they're fetched and checked here, as the Vega SDK is.
#   https://developer.amazon.com/docs/vega/0.24/media-player-hls-player.html
set -euo pipefail
cd "$(dirname "$0")/.."

RELEASE="hls-rel-v1.5.11-r1.7-devices_scope"
URL="https://amzndevresources.com/vega/media-player/$RELEASE.tar.gz"
SHA256="cc652c54357e071a2a9af9031bc8963e6c4ef3b234f7e729e411c7bf43a1e44e"
OUT="src/hls"

# hls.js's XMLHttpRequest loader, exported for the app: Amazon's build loads with fetch,
# and on the stick fetch's arrayBuffer() never settles, so no segment ever arrives (the
# playlists, read as text, do). Vega's XMLHttpRequest takes 'arraybuffer' directly.
export_xhr_loader() {
  local mjs="$OUT/player/hlsjsplayer/dist/hls.mjs"
  grep -q '^export { XhrLoader };$' "$mjs" || printf '\nexport { XhrLoader };\n' >> "$mjs"
}

if [ -f "$OUT/.release" ] && [ "$(cat "$OUT/.release")" = "$RELEASE" ]; then
  export_xhr_loader
  echo "hls.js for Vega is already built ($RELEASE)."
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL "$URL" -o "$work/rel.tgz"
echo "$SHA256  $work/rel.tgz" | sha256sum -c -
mkdir "$work/rel"
tar -xzf "$work/rel.tgz" -C "$work/rel" 2>/dev/null
rel="$work/rel/hls-rel"

git clone -q --depth 1 --branch v1.5.11 https://github.com/video-dev/hls.js.git "$work/hls.js"
(
  cd "$work/hls.js"
  git -c user.name=build -c user.email=build@localhost apply -3 "$rel"/hls-patch/[0-9]*.patch
  npm ci --no-audit --no-fund --silent
  npm run build --silent
)

# Laid out as Amazon's sample has them: the player's base beside its interface, the hls.js
# player under it, and (as PlayerBase asks for it a folder up) the interface there too.
rm -rf "$OUT"
mkdir -p "$OUT/player/hlsjsplayer/dist" "$OUT/polyfills"
cp "$rel"/src/PlayerBase.ts "$rel"/src/PlayerInterface.ts "$OUT/player/"
cp "$rel"/src/PlayerInterface.ts "$OUT/"
cp "$rel"/src/hlsjsplayer/HlsJsPlayer.ts "$OUT/player/hlsjsplayer/"
cp "$rel"/polyfills/*.ts "$OUT/polyfills/"
cp "$work"/hls.js/dist/hls.mjs "$OUT/player/hlsjsplayer/dist/"
# Amazon's files are type-checked loosely, as Amazon wrote them; the app's own strictly.
for f in "$OUT"/*.ts "$OUT"/player/*.ts "$OUT"/player/hlsjsplayer/*.ts "$OUT"/polyfills/*.ts; do
  grep -q '@ts-nocheck' "$f" || sed -i '1i // @ts-nocheck' "$f"
done
export_xhr_loader
echo "$RELEASE" > "$OUT/.release"
echo "hls.js for Vega built into $OUT."
