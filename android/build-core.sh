#!/usr/bin/env bash
# Cross-compile the Go core into a per-ABI libwoocore.so for Android, using the
# NDK's clang as the cgo C compiler. Drops each .so into app/src/main/jniLibs/.
#
#   export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/<version>
#   ./build-core.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$(cd "$HERE/../core" && pwd)"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK path}"

API=24
HOST="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"   # darwin-x86_64 / linux-x86_64
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"

build() { # abi goarch clang-triple [goarm]
  local abi="$1" goarch="$2" triple="$3" goarm="${4:-}"
  local out="$HERE/app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  echo "building $abi ($goarch)…"
  (
    cd "$CORE"
    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH="$goarch"
    [ -n "$goarm" ] && export GOARM="$goarm"
    export CC="$TOOLCHAIN/${triple}${API}-clang"
    # -soname=libwoocore.so: so anything linking against this records its
    #   DT_NEEDED by name, not by the build-time path (otherwise the JNI shim
    #   looks for /workspace/.../libwoocore.so on-device and dlopen fails).
    # -z max-page-size=16384: load on Android 15+ 16 KB-page devices.
    go build -buildmode=c-shared \
      -ldflags="-extldflags '-Wl,-soname,libwoocore.so -Wl,-z,max-page-size=16384'" \
      -o "$out/libwoocore.so" ./ffi
  )
}

build arm64-v8a   arm64 aarch64-linux-android
build armeabi-v7a arm   armv7a-linux-androideabi 7
build x86_64      amd64 x86_64-linux-android

# Keep the JNI shim's header in sync with the core's authoritative ABI.
cp "$CORE/ffi/woocore.h" "$HERE/app/src/main/cpp/include/woocore.h"
echo "done. open ./android in Android Studio (or: ./gradlew :app:assembleDebug)"
