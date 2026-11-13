#!/bin/bash
# Builds PokeDaisy for iPad and runs it in the iPad simulator (a Mac with Xcode and CMake 3.20+).
#
#   ios/build.sh [ROM_OR_SAVE ...]
#
# Any files given (.gba / .gb / .gbc, and .sav / .srm saves named like their game) are copied into the app's
# Documents first, so the game list has them. Nothing is copied out of the repo: games stay on your Mac.
# SIM="iPad Air 11-inch (M3)" picks the simulator (default: the first iPad available).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
BUILD="$REPO/build/ios"
BUNDLE=com.pokedaisy.ipad

[ -f third_party/mgba/CMakeLists.txt ] || git submodule update --init third_party/mgba

cmake -S ios -B "$BUILD" -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_OSX_ARCHITECTURES="$(uname -m)" >/dev/null
xcodebuild -project "$BUILD/PokeDaisy.xcodeproj" -target PokeDaisy -configuration Debug -sdk iphonesimulator \
    -arch "$(uname -m)" CODE_SIGNING_ALLOWED=NO build >"$BUILD/xcodebuild.log" 2>&1 || {
    grep -E "error:" "$BUILD/xcodebuild.log" | head -40; echo "build failed: $BUILD/xcodebuild.log"; exit 1; }
APP=$(find "$BUILD" -name PokeDaisy.app -path '*iphonesimulator*' -type d | head -1)
[ -n "$APP" ] || { echo "build failed (no PokeDaisy.app)"; exit 1; }

# The simulator: a booted iPad, else the one asked for, else the first iPad there is.
UDID=$(xcrun simctl list devices booted | grep -i ipad | grep -oE '[0-9A-F-]{36}' | head -1 || true)
if [ -z "$UDID" ]; then
    NAME=${SIM:-}
    if [ -n "$NAME" ]; then
        UDID=$(xcrun simctl list devices available | grep -F "$NAME (" | grep -oE '[0-9A-F-]{36}' | head -1)
    else
        UDID=$(xcrun simctl list devices available | grep -i ipad | grep -oE '[0-9A-F-]{36}' | head -1)
    fi
    [ -n "$UDID" ] || { echo "no iPad simulator: add one in Xcode > Settings > Components"; exit 1; }
    xcrun simctl boot "$UDID"
fi
# The simulator's window: Simulator.app, or DeviceHub.app from Xcode 27 on (it has no Simulator.app); the app runs
# without either, so a missing one isn't an error.
open -a Simulator 2>/dev/null || open "$(xcode-select -p)/../Applications/DeviceHub.app" 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"
if [ $# -gt 0 ]; then
    DOCS="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)/Documents"
    mkdir -p "$DOCS/saves"
    for f in "$@"; do
        case "${f##*.}" in
            sav|srm|SAV|SRM) cp -n "$f" "$DOCS/saves/" ;;
            *) cp "$f" "$DOCS/" ;;
        esac
    done
fi
xcrun simctl launch "$UDID" "$BUNDLE"
echo "running on simulator $UDID"
