#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "release tag must match vMAJOR.MINOR.PATCH (example: v1.2.3)" >&2
    exit 1
fi

major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))

if (( minor > 999 || patch > 999 )); then
    echo "minor and patch versions must be between 0 and 999" >&2
    exit 1
fi

version="${major}.${minor}.${patch}"
version_code=$((major * 1000000 + minor * 1000 + patch))

if (( version_code < 1 || version_code > 2100000000 )); then
    echo "calculated Android versionCode is outside the supported range" >&2
    exit 1
fi

printf 'tag=%s\nversion=%s\nversion_code=%s\n' "$tag" "$version" "$version_code"
