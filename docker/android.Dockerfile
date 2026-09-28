# Reproducible Android build environment: Go + Android SDK/NDK + Gradle.
# Build the APK without installing any of it on your host.
#
# Runs as linux/amd64 (forced by the CLI --platform flag in build-android.sh):
# the Android NDK only ships a linux-x86_64 host toolchain, so on Apple Silicon
# this runs under emulation. Every network step has retries and lives in its own
# cache layer, so a truncated download on a flaky connection only costs that one
# step on re-run — completed layers are reused.
#
# Go is unpacked in a native stage: under Rosetta on macOS 27, GNU tar's
# directory-relative syscalls (mkdirat/openat on a dir fd) fail with ENOSYS,
# so extracting a tarball inside the amd64 image breaks. COPY is not emulated.
ARG GO_VERSION=1.26.3
FROM --platform=$BUILDPLATFORM alpine:3.20 AS go
ARG GO_VERSION
RUN apk add --no-cache curl tar \
    && curl -fsSL --retry 8 --retry-delay 5 --retry-all-errors \
        https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz | tar -C /usr/local -xz

FROM eclipse-temurin:17-jdk

ENV DEBIAN_FRONTEND=noninteractive

# apt with retries (flaky-network resilience).
RUN printf 'Acquire::Retries "8";\nAcquire::http::Timeout "30";\n' > /etc/apt/apt.conf.d/80-retries \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Shared curl flags: retry hard on any transient error.
ENV CURL="curl -fsSL --retry 8 --retry-delay 5 --retry-all-errors"

# --- Go (must satisfy core/go.mod: go 1.26) ---------------------------------
COPY --from=go /usr/local/go /usr/local/go
ENV PATH=/usr/local/go/bin:${PATH}

# --- Gradle (AGP 8.5 needs Gradle >= 8.7) -----------------------------------
ENV GRADLE_VERSION=8.9
RUN $CURL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/g.zip \
    && unzip -q /tmp/g.zip -d /opt && rm /tmp/g.zip
ENV PATH=/opt/gradle-${GRADLE_VERSION}/bin:${PATH}

# --- Android SDK command-line tools -----------------------------------------
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && $CURL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && rm /tmp/clt.zip \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest
ENV PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

# Accept licenses once.
RUN yes | sdkmanager --licenses >/dev/null

# --- SDK packages, each in its own cache layer (lightest first, NDK last) ----
# A truncated download only re-runs that single package on retry.
RUN sdkmanager --install "platform-tools" >/dev/null
RUN sdkmanager --install "platforms;android-34" >/dev/null
RUN sdkmanager --install "build-tools;34.0.0" >/dev/null
RUN sdkmanager --install "cmake;3.22.1" >/dev/null

ENV ANDROID_NDK_VERSION=26.3.11579264
RUN sdkmanager --install "ndk;${ANDROID_NDK_VERSION}" >/dev/null
ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/${ANDROID_NDK_VERSION}

WORKDIR /workspace
