#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

if [[ ! -f "${repository_root}/MPVKit/Package.swift" ]]; then
    git -C "${repository_root}" submodule update --init --depth 1 MPVKit
fi

patch="$repository_root/scripts/nuvio-engine-torrent-download.patch"
patch_checksum="$(shasum -a 256 "$patch" | awk '{print $1}')"
stamp="$engine_framework/.nuvio-torrent-patch"
if [[ -f "$engine_framework/Info.plist" && -f "$stamp" &&
      "$(cat "$stamp")" == "$patch_checksum" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT
built_framework="$temporary_directory/NuvioEngine.xcframework"
"$repository_root/scripts/prepare-torrent-engine.sh" ios "$built_framework"
test -f "$built_framework/ios-arm64/libCNuvioEngine.a"

mkdir -p "$(dirname "$engine_framework")"
rm -rf "$engine_framework"
mv "$built_framework" "$engine_framework"
printf '%s\n' "$patch_checksum" > "$stamp"
