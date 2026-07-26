#!/usr/bin/env bash
# Cross-build the Go core into the static archive the macOS app links against.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$(cd "$HERE/../core" && pwd)"
OUT="$HERE/Vendor/woocore"
mkdir -p "$OUT"

echo "building libwoocore.a (host arch)…"
( cd "$CORE" && \
  MACOSX_DEPLOYMENT_TARGET=13.0 \
  CGO_ENABLED=1 \
  CGO_CFLAGS="-mmacosx-version-min=13.0" \
  CGO_LDFLAGS="-mmacosx-version-min=13.0" \
  go build -buildmode=c-archive -o "$OUT/libwoocore.a" ./ffi )

# Use the hand-written, const-correct header for Swift. The cgo-generated header
# declares params as char* (non-const), which clashes with Swift's withCString
# const pointers; the hand-written one is ABI-identical but const-correct.
cp "$CORE/ffi/woocore.h" "$HERE/Sources/CWooCore/woocore.h"
echo "done -> $OUT/libwoocore.a"
echo "now: swift build   (or: swift run WiglyWoo)"
