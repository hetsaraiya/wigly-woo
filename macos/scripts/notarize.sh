#!/usr/bin/env bash
# Developer ID sign, notarize, and staple. Phases 6–8 need this so TCC
# grants and the camera extension survive an upgrade. Without the certs,
# the script explains what is missing and leaves the ad-hoc build alone.
set -euo pipefail
APP="${1:-macos/dist/WiglyWoo.app}"
if [[ -z "${APPLE_DEVELOPER_ID:-}" || -z "${NOTARY_KEYCHAIN_PROFILE:-}" ]]; then
  echo "Developer ID signing is not configured."
  echo "Set APPLE_DEVELOPER_ID to the certificate name and NOTARY_KEYCHAIN_PROFILE to a notarytool profile."
  echo "The ad-hoc build still runs. Accessibility grants reset on each upgrade, and the camera extension cannot be installed."
  exit 0
fi
codesign --force --options runtime --timestamp --sign "$APPLE_DEVELOPER_ID" "$APP"
ditto -c -k --keepParent "$APP" /tmp/wigly-woo-notary.zip
xcrun notarytool submit /tmp/wigly-woo-notary.zip --keychain-profile "$NOTARY_KEYCHAIN_PROFILE" --wait
xcrun stapler staple "$APP"
rm -f /tmp/wigly-woo-notary.zip
echo "Notarized $APP"
