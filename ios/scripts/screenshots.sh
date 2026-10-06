#!/bin/bash
# Screenshots of the built apps on an iPhone and an Apple TV simulator, each scene of the
# stand-in server (see DemoTransport): signed out, the sign-in code, and Home.
set -euo pipefail
products="$1"
out="${2:-shots}"
mkdir -p "$out"

device() {
  xcrun simctl list devices available -j | python3 -c "
import json, sys
want = sys.argv[1]
devices = [d for runtime, ds in json.load(sys.stdin)['devices'].items() for d in ds if want in d['name']]
# The newest model the runner has.
devices.sort(key=lambda d: d['name'])
print(devices[-1]['udid'] if devices else '')
" "$1"
}

shoot() {
  local name="$1" app="$2" bundle="$3" want="$4"
  local udid
  udid=$(device "$want")
  if [ -z "$udid" ]; then echo "No $want simulator"; return 1; fi
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b
  xcrun simctl install "$udid" "$app"
  for scene in signin code home movie show library; do
    xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
    xcrun simctl launch "$udid" "$bundle" -demo "$scene"
    sleep 8
    xcrun simctl io "$udid" screenshot "$out/$name-$scene.png"
    echo "shot $name-$scene"
  done
  xcrun simctl shutdown "$udid" || true
}

shoot iphone "$products/Debug-iphonesimulator/Reely.app" tv.reely.app "iPhone"
shoot appletv "$products/Debug-appletvsimulator/Reely.app" tv.reely.tv "Apple TV 4K"
