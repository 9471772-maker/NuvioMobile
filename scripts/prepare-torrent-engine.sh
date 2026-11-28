#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_commit=167f8541d2e07212a9cf7034b9ca4a0c3b6be4d3
patch="$repository_root/scripts/nuvio-engine-torrent-download.patch"

if [[ $# -ne 2 || ( "$1" != "ios" && "$1" != "android" ) ]]; then
    echo "Usage: $0 ios|android OUTPUT_PATH" >&2
    exit 2
fi
platform="$1"
output="$2"
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/nuvio-engine.XXXXXX")"
trap 'rm -rf "$work"' EXIT

engine="$work/source"
git clone --quiet --depth 1 --branch v0.1.6 https://github.com/NuvioMedia/nuvio-engine.git "$engine"
test "$(git -C "$engine" rev-parse HEAD)" = "$engine_commit"
git -C "$engine" apply "$patch"

case "$platform" in
    ios)
        "$engine/scripts/build-apple-xcframework.sh"
        mkdir -p "$(dirname "$output")"
        ditto "$engine/platform/apple/NuvioEngine.xcframework" "$output"
        ;;
    android)
        sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        if [[ -z "$sdk_root" ]]; then
            echo "ANDROID_HOME or ANDROID_SDK_ROOT must locate the Android SDK" >&2
            exit 1
        fi
        ndk_version=29.0.14206865
        cmake_version=4.1.2
        if [[ ! -x "$sdk_root/ndk/$ndk_version/ndk-build" ||
              ! -x "$sdk_root/cmake/$cmake_version/bin/cmake" ||
              ! -f "$sdk_root/platforms/android-36/android.jar" ]]; then
            sdkmanager --sdk_root="$sdk_root" "platforms;android-36" \
                "ndk;$ndk_version" "cmake;$cmake_version"
        fi
        export PATH="$sdk_root/cmake/$cmake_version/bin:$PATH"
        (
            cd "$engine/platform/android"
            ./gradlew --no-daemon :engine:assembleRelease
        )
        mkdir -p "$(dirname "$output")"
        cp "$engine/platform/android/engine/build/outputs/aar/engine-release.aar" "$output"
        ;;
esac
