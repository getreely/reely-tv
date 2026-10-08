#!/bin/bash
# VLC's player engine for the files and streams Apple's player can't open (an MKV film from
# an IPTV provider, a channel that's bare MPEG-TS). VideoLAN's own build of VLCKit 4, one
# framework for iPhone, iPad and Apple TV, checked against the checksum below and unpacked
# into Vendor/ for the Xcode project. VLCKit 4 is what VLC's own apps use, and the one with
# picture in picture; VideoLAN still calls it a pre-release. Not kept in the repository: run
# before `xcodegen generate`. LGPL 2.1, linked dynamically.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=VLCKit
VERSION=4.0.0a19-d7597c1706-85a537d69
SUM=1172078a43150af202c31feb62db3d6687f242d3aa048cce1b899f51c4f14142
BASE=https://download.videolan.org/pub/cocoapods/unstable
if [ -d "Vendor/$NAME.xcframework" ]; then echo "$NAME already here"; ls Vendor; exit 0; fi
mkdir -p Vendor
curl -fsSL --retry 3 -o "Vendor/$NAME.tar.xz" "$BASE/$NAME-$VERSION.tar.xz"
echo "$SUM  Vendor/$NAME.tar.xz" | shasum -a 256 -c -
# Only the slices the apps are built for: the framework carries macOS, watchOS and visionOS too.
tar -xJf "Vendor/$NAME.tar.xz" -C Vendor --strip-components=1 \
  "$NAME-binary/$NAME.xcframework/Info.plist" \
  "$NAME-binary/$NAME.xcframework/ios-arm64" "$NAME-binary/$NAME.xcframework/ios-arm64_x86_64-simulator" \
  "$NAME-binary/$NAME.xcframework/tvos-arm64" "$NAME-binary/$NAME.xcframework/tvos-arm64_x86_64-simulator"
rm "Vendor/$NAME.tar.xz"
# The rest of the slices were left out above; the framework's list of them has to agree.
/usr/libexec/PlistBuddy -c "Print :AvailableLibraries" "Vendor/$NAME.xcframework/Info.plist" >/dev/null 2>&1 && python3 - "Vendor/$NAME.xcframework/Info.plist" <<'PY'
import plistlib, sys
path = sys.argv[1]
with open(path, "rb") as f: info = plistlib.load(f)
info["AvailableLibraries"] = [l for l in info["AvailableLibraries"] if l.get("SupportedPlatform") in ("ios", "tvos")]
with open(path, "wb") as f: plistlib.dump(info, f)
PY
find Vendor -name "._*" -delete
ls Vendor
