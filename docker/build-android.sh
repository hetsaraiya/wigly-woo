#!/usr/bin/env bash
# One-shot Android build in Docker. Produces a real APK on your host without
# installing Go / the Android SDK / NDK / Gradle locally — only Docker needed.
#
#   docker/build-android.sh
#
# Output: android/app/build/outputs/apk/debug/app-debug.apk
#
# Network resilience: Docker Hub auth/pulls can time out on flaky links, which
# fails before any Dockerfile retry can help. So the image pull and build are
# each retried at the CLI level; completed layers are cached, so attempts make
# forward progress rather than restarting.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="wiglywoo-android"
PLATFORM="linux/amd64"   # NDK host toolchain is x86_64 only
RETRIES=8

retry() { # retry <label> <cmd...>
    local label="$1"; shift
    local n=1
    until "$@"; do
        if [ "$n" -ge "$RETRIES" ]; then
            echo ">> ${label}: failed after ${RETRIES} attempts" >&2
            return 1
        fi
        echo ">> ${label}: attempt ${n} failed (likely network) — retrying in 10s…" >&2
        n=$((n + 1)); sleep 10
    done
}

# Pre-pull the base image so the build's metadata step resolves locally and
# doesn't depend on auth.docker.io being reachable at build time.
echo ">> pulling base image (retried)…"
retry "base image pull" docker pull --platform "${PLATFORM}" eclipse-temurin:17-jdk || exit 1

echo ">> building image ${IMAGE} (downloads SDK/NDK on first run, ~few GB)…"
retry "image build" docker build --platform "${PLATFORM}" -t "${IMAGE}" \
    -f "${ROOT}/docker/android.Dockerfile" "${ROOT}/docker" || exit 1

echo ">> compiling Go core (.so per ABI) + assembling APK…"
retry "apk build" docker run --rm --platform "${PLATFORM}" \
    -v "${ROOT}":/workspace \
    -v wiglywoo-gradle:/root/.gradle \
    -v wiglywoo-gocache:/root/.cache/go-build \
    -w /workspace \
    "${IMAGE}" bash -lc '
        set -e
        cd android
        ./build-core.sh
        gradle --no-daemon :app:assembleDebug
    ' || exit 1

APK="${ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
echo ""
if [ -f "${APK}" ]; then
    echo ">> done: ${APK}"
    echo "   install on a device:  adb install -r \"${APK}\""
else
    echo ">> build finished but APK not found at ${APK}" >&2
    exit 1
fi
