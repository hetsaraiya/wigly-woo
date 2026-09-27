#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="${WIGLY_VERSION_NAME:-0.2.0}"
BUILD_NUMBER="${WIGLY_BUILD_NUMBER:-1}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "invalid WIGLY_VERSION_NAME: $VERSION" >&2
    exit 1
fi
if [[ ! "$BUILD_NUMBER" =~ ^[0-9]+$ ]]; then
    echo "invalid WIGLY_BUILD_NUMBER: $BUILD_NUMBER" >&2
    exit 1
fi

./build-core.sh
swift build -c release
BIN_DIR="$(swift build -c release --show-bin-path)"
APP="$ROOT/dist/WiglyWoo.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/WiglyWoo" "$APP/Contents/MacOS/WiglyWoo"
cp "$ROOT/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/Resources/WiglyWoo.icns" "$APP/Contents/Resources/WiglyWoo.icns"
cp -R "$ROOT/Resources/Fonts" "$APP/Contents/Resources/Fonts"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$APP/Contents/Info.plist"

# Free distribution uses an ad-hoc signature. It preserves bundle integrity,
# but users must approve the app once in System Settings because it is not
# Developer ID signed or notarized.
codesign --force --sign - "$APP/Contents/MacOS/WiglyWoo"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"

echo "Built: $APP"
echo "Open with: open '$APP'"
