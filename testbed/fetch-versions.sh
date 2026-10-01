#!/usr/bin/env bash
# Fetches Paper server jars for the version matrix from PaperMC's own download API, and checks each against the SHA-256
# PaperMC publishes. Jars land in run/downloads/paper-<version>.jar.
#
#   bash fetch-versions.sh 1.20.6 1.21.1 1.21.3 1.21.4 1.21.5 1.21.8 1.21.11 26.1.2 26.2
set -u
mkdir -p "$(dirname "$0")/run/downloads"
cd "$(dirname "$0")/run" || exit 1
log() { echo "[$(date +%H:%M:%S)] $*"; }

fetch() {
  local json url sha name
  json=$(curl -s --max-time 30 "https://fill.papermc.io/v3/projects/paper/versions/$1/builds/latest")
  url=$(printf '%s' "$json" | python -c "import sys,json; print(json.load(sys.stdin)['downloads']['server:default']['url'])")
  sha=$(printf '%s' "$json" | python -c "import sys,json; print(json.load(sys.stdin)['downloads']['server:default']['checksums']['sha256'])")
  name="paper-$1.jar"
  curl -sSL --max-time 900 -o "downloads/$name" "$url" || { log "FAIL download $name"; return 1; }
  if [ "$(sha256sum "downloads/$name" | cut -d' ' -f1)" = "$sha" ]; then log "ok $name sha256 $sha"; else log "FAIL checksum $name"; rm -f "downloads/$name"; fi
}

for version in "$@"; do fetch "$version" & done
wait
log "all fetches finished"
