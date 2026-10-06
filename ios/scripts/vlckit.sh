#!/bin/bash
# VLC's player engine for the files and streams Apple's player can't open (an MKV film from
# an IPTV provider, a channel that's bare MPEG-TS). VideoLAN's own builds of VLCKit, checked
# against the checksums below, unpacked into Vendor/ for the Xcode project. Not kept in the
# repository: run before `xcodegen generate`. LGPL 2.1, linked dynamically.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=3.7.3-319ed2c0-79128878
BASE=https://download.videolan.org/pub/cocoapods/prod
fetch() {
  local name="$1" sum="$2"
  if [ -d "Vendor/$name.xcframework" ]; then echo "$name already here"; return; fi
  mkdir -p Vendor
  curl -fsSL --retry 3 -o "Vendor/$name.tar.xz" "$BASE/$name-$VERSION.tar.xz"
  echo "$sum  Vendor/$name.tar.xz" | shasum -a 256 -c -
  tar -xJf "Vendor/$name.tar.xz" -C Vendor --strip-components=1 "$name-binary/$name.xcframework"
  rm "Vendor/$name.tar.xz"
}
fetch MobileVLCKit 0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9
fetch TVVLCKit b5f90c226ed54d9dc1c03901c60dc7749b74a53caace2c3047e4c0b7a063e46c
ls Vendor
