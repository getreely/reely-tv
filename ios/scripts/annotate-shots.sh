#!/bin/bash
# The screenshots as build notes (annotations), a JPEG in base64 split into parts, so they
# can be read back through GitHub's API: artifacts are served from a host it can't reach.
set -euo pipefail
dir="${1:-shots}"
for png in "$dir"/*.png; do
  name=$(basename "$png" .png)
  jpg="$dir/$name.jpg"
  sips -s format jpeg -s formatOptions 55 --resampleWidth 900 "$png" --out "$jpg" >/dev/null
  data=$(base64 -i "$jpg" | tr -d '\n')
  size=${#data}
  part=0
  parts=$(( (size + 59999) / 60000 ))
  while [ $((part * 60000)) -lt "$size" ]; do
    echo "::notice title=shot $name $((part + 1))/$parts::${data:$((part * 60000)):60000}"
    part=$((part + 1))
  done
done
