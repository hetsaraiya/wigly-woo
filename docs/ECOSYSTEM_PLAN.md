# Wigly Woo — "Apple‑ecosystem" plan for Android + Mac

Status: implemented in the tree, 2026‑09‑28. The feature table's original
research labels are kept below. Code now covers mirroring, the continuity
relays, calls and SMS, hotspot and BLE presence, Touch ID phone unlock,
edge-pointer control, and the camera-extension project. Device spikes are
recorded here and were not all run.

### Phase 0 spike results (this Mac and the attached A52s)

Probed with adb on SM-A528B, Android 14, One UI 6.1. The shell package has
`INJECT_EVENTS`, `READ_FRAME_BUFFER`, `CAPTURE_VIDEO_OUTPUT`,
`CAPTURE_AUDIO_OUTPUT`, `CONTROL_KEYGUARD`, `MANAGE_ACTIVITY_TASKS`,
`NETWORK_SETTINGS`, `TETHER_PRIVILEGED`, and `MODIFY_PHONE_STATE` granted.

| Spike | Result |
|---|---|
| scrcpy latency ceiling | Not run. `scrcpy` is not installed, and this session did not install it. The encoder still targets 1080p-class HEVC at 8 Mbit/s and 60 fps, and drops late frames. |
| `MirrorServer` as uid 2000 | Not executed on the phone. Hidden APIs are feature-probed and a failed probe disables that feature instead of crashing. The basic MediaProjection path remains. |
| `TetheringManager.startTethering` | Not called on device. `cmd wifi start-softap` exists and rejects a bare invocation (`Argument expected after "start-softap"`), which matches the plan: use the API, and fall back to the hotspot settings panel when the API returns an error. |
| `VOICE_CALL_DOWNLINK` | Not run. It needs a live cellular call. Call audio stays on the phone, as the plan already concluded for the two-way path. |

The Mac has Command Line Tools but not a full Xcode, so the camera system
extension source and `project.yml` are in the tree and were not built here.
`macos/scripts/notarize.sh` refuses to pretend a Developer ID is present.

## 0. The two devices this plan is written against

Probed directly (adb, `sysctl`, `system_profiler`), not guessed:

| | Phone | Mac |
|---|---|---|
| Model | Samsung Galaxy **A52s 5G**, SM‑A528B (`a52sxq`) | MacBook, **Apple M5** (`Mac17,3`) |
| OS | **Android 14**, One UI 6.1 (API 34), patch A528BXXSBGYI3 — final OS update for this model | **macOS 27.0 "Golden Gate"** (26A428) |
| SoC / codecs | Snapdragon 778G (`lahaina`), Adreno; HW H.264 + HEVC encode | M5; HW H.264 / HEVC / AV1 decode (VideoToolbox) |
| Screen | 1080×2400, 120 Hz | — |
| Radios | BT LE, NFC (+HCE), **Wi‑Fi Direct, Wi‑Fi Aware**, **no UWB** | Apple **N1** (Wi‑Fi 7 / BT 6), BT profiles HFP/A2DP/AVRCP/HID/LEA/GATT, **no UWB**, no NFC, no Wi‑Fi Aware |
| Other | freeform windows + PiP supported, companion_device_setup, second Android user 150 (Secure Folder) | Touch ID |
| Privilege | **Shizuku 13.6.0** running as `shell` (uid 2000), started via wireless debugging | App is **ad‑hoc signed** (brew cask), macOS 13+ target |

What `shell` can do on *this* phone (checked with `dumpsys package com.android.shell`):
`INJECT_EVENTS`, `READ_FRAME_BUFFER`, `CAPTURE_VIDEO_OUTPUT`, `CAPTURE_AUDIO_OUTPUT`,
`CONTROL_KEYGUARD`, `MANAGE_ACTIVITY_TASKS`, `NETWORK_SETTINGS`, `TETHER_PRIVILEGED`,
`MODIFY_PHONE_STATE` — all **granted**. Nearly every "Apple‑like" feature below
depends on that list. Shizuku is how our app borrows those permissions, and it
already works in this codebase (`PrivilegedClipboardService` is a Shizuku UserService).

Two limits hold throughout the plan:

1. **Shizuku dies on every phone reboot.** On Android 11+ without root, the user
   restarts it via Wireless debugging (needs Wi‑Fi). Every feature marked
   *(Shizuku)* degrades gracefully until then: the UI must say "Privileged
   features paused — restart Shizuku", never fail silently.
2. **The Mac app is ad‑hoc signed.** macOS keys TCC grants (Accessibility,
   Screen Recording, Input Monitoring) to the code signature. An ad‑hoc
   signature changes every build, so each `brew upgrade` silently revokes them.
   System extensions (the webcam feature) *cannot* be installed at all without
   Developer ID signing and notarization. → **A $99/yr Apple Developer ID is a
   prerequisite for Phases 6–8.** Phases 1–5 work without it.

---

## 1. Feature 1 — Phone Mirroring, one click from the app

### What Apple does
iPhone Mirroring (macOS 15+) shows the phone in a Mac window. You control it with
the trackpad and keyboard, and the phone stays locked with its screen dark. macOS 27 adds
resizable windows, Control Center access and DRM video.

### How we do it (the scrcpy model, without the USB cable or the terminal)
[scrcpy](https://github.com/Genymobile/scrcpy) (Apache‑2.0, v4.1) proved the
approach. A small Java "server" runs as `shell`. It creates a virtual display
mirroring the screen, encodes it with MediaCodec, captures audio via
`REMOTE_SUBMIX`/playback capture, and injects touches and keys with
`InputManager.injectInputEvent`. scrcpy starts that server through `adb`.
**We start it through Shizuku** (`Shizuku.bindUserService`), which runs our
class under `app_process` as uid 2000. That is the same mechanism our clipboard
watcher already uses. No cable, no `adb` on the Mac, no terminal.

```
 Mac (Swift)                                   Phone
 ┌─────────────────────────┐   relay "mirror_request"   ┌──────────────────────────┐
 │ ⌘⇧M / menu bar / button │ ─────────────────────────▶ │ CompanionManager wakes   │
 │ MirrorController        │                            │ ShizukuMirrorBridge      │
 │                         │   LAN mTLS session (core)  │  └▶ MirrorServer (uid 2000)
 │ VideoDecoder (VT HEVC)  │ ◀── video ch (HEVC AUs) ── │     ScreenEncoder (MediaCodec)
 │ AudioPlayer (Opus/AAC)  │ ◀── audio ch ───────────── │     AudioCapture (submix)
 │ InputMapper             │ ── control ch ───────────▶ │     Controller (inject)
 │ AVSampleBufferDisplay…  │ ◀─▶ clipboard/device msgs  │     DeviceMessages
 └─────────────────────────┘                            └──────────────────────────┘
```

**One click:**
- Sidebar item "Phone", a menu‑bar button "Mirror phone", and a global hotkey
  ⌘⇧M (Carbon `RegisterEventHotKey`, which needs no TCC permission).
- The Mac sends `mirror_request` over the existing encrypted relay. The phone's
  foreground `CompanionForegroundService` receives it and binds the Shizuku
  mirror service. The phone then opens a LAN session back to the Mac on its
  already-trusted address.
- The window appears in about 1 s. When there's no shared LAN, the window
  offers "Turn on phone hotspot and connect" (table #29).

**Fallback when Shizuku is off:**
- Capture uses MediaProjection. Android 14 asks for consent on *every* session,
  and a foreground service of type `mediaProjection` is required.
- Input uses an AccessibilityService `dispatchGesture` for taps and swipes. Our
  existing Wigly IME (`WiglyInputMethodService`) handles typing.
- It's clunkier: a consent dialog on each start, no audio from apps that opt
  out, and gestures that are slower than real injection. It still works, and the
  UI calls it "basic mirroring".

### Mirroring sub‑features

| Sub‑feature | Approach | A52s/Android 14 |
|---|---|---|
| View + control (tap, scroll, drag, right‑click = Back, middle = Home) | scrcpy‑style inject | ✅ |
| Mac keyboard → phone (incl. shortcuts, IME‑less text) | `KeyEvent` inject; text via `InputConnection` for non‑ASCII (our IME) | ✅ |
| Audio from phone on Mac | playback capture (Android 11+), Opus → AudioToolbox | ✅ |
| Phone screen dark while mirroring (privacy) | `SurfaceControl.setDisplayPowerMode(OFF)` like `scrcpy -S` — panel off, display still rendering | ✅ |
| Works while phone "locked" | Not truly: keyguard shows on the mirror. Pair with **Unlock phone from Mac** (table #20) for a one‑click unlock | ⚠️ partial |
| Resizable window / aspect | Mac window letterboxes; rotation follows phone | ✅ |
| Per‑app windows (macOS 27 "resizable apps") | `--new-display` model: create a virtual display at the Mac window size, launch the app on it via `ActivityOptions.setLaunchDisplayId` (shell has `MANAGE_ACTIVITY_TASKS`). **Live resizing** (scrcpy "flex display") needs **Android 15+** | ⚠️ fixed size only on A52s; flex on Android 15+ phones |
| Control Center | `cmd statusbar expand-settings` / `expand-notifications` buttons in the title bar | ✅ |
| Drag files Mac → phone | drop on mirror window → existing file transfer → `ACTION_VIEW`/save to Downloads | ✅ |
| Drag phone → Mac | Android exposes no drag payload to other processes. Instead: a "Recent" shelf (MediaStore latest photos/files/screenshots) you drag *out of* | ⚠️ partial |
| Clipboard inside mirror | already synced; mirror also sets clipboard via shell before ⌘V | ✅ |
| Notification click → opens that app in the mirror | `am start` the package / `contentIntent.send()` from shell | ✅ |
| App launcher on Mac (search phone apps, open directly) | list launcher activities + icons from the app process; launch via shell | ✅ |
| Record phone screen on Mac | mux the incoming HEVC stream to `.mov` (AVAssetWriter, passthrough) | ✅ |
| DRM video (Netflix etc.), banking apps | `FLAG_SECURE` / secure (Widevine L1) surfaces render **black** in any capture. Apple can do it in macOS 27 because it owns both ends | ❌ not possible |
| Gamepad as HID | scrcpy UHID; Android 15+ for virtual‑display association | optional |

**Latency budget.** Target ≤ 60 ms from glass to glass on 5 GHz LAN:
1080×2400 → 1080p‑class HEVC at 8 Mbit/s and 60 fps on the 778G, with
VideoToolbox hardware decode on the M5. Use low‑latency encoder keys
(`KEY_LATENCY=0`, `KEY_PRIORITY=0`, repeat-previous-frame), send each access unit
as one frame, and let the decoder *drop* late frames rather than queue them.

---

## 2. Feature 2 — the rest of the ecosystem, researched one by one

Legend: ✅ Implemented · 🟢 Implementable on your devices · 🟡 Partial / with caveats ·
🔵 Works natively (no code, just setup) · 🔴 Not possible (reason given).

### 2.1 Already in Wigly Woo
- **AirDrop ↔ file transfer.** LAN mTLS transfer is implemented, with a queue,
  history, per-device trust and "Always accept".
- **Universal Clipboard.** Encrypted relay sync is implemented. A Shizuku
  watcher reads copies in the background, which normal Android 10+ apps cannot
  do.
- **iPhone notifications on Mac.**
  - Relay of notifications, with *reply* via RemoteInput and *dismiss* sync.
  - The Android system and System UI packages are filtered out.
- **Remote keyboard.** The Mac types into the phone through the Wigly IME
  (insert/delete/enter).

### 2.2 Continuity features and how they map
**3. Handoff.**
- Apple hands the *current activity* (web page, document) between devices.
- On Android, one app can't read another app's state. So it's 🟡:
  - Browser URL Mac→phone: read the front tab of Safari, Chrome or Arc via
    AppleScript (needs Automation permission), then offer "Open on phone" in the
    menu bar. The phone opens it with `ACTION_VIEW`.
  - Phone→Mac: a Share-sheet target ("Send to Mac", which exists) plus a
    "Continue on Mac" quick-settings tile. The tile reads the foreground app;
    with Shizuku, `dumpsys activity` gives the top package. It then asks that
    app for a URL through the Accessibility tree of Chrome's URL bar
    (opt-in only).
- Chrome's own "tabs from other devices" covers the rest natively 🔵.

**4. Universal Control** (the Mac cursor slides off the screen edge onto the
phone) 🟢.
- A `CGEventTap` on the Mac detects the pointer at the chosen edge, hides the
  cursor and forwards mouse and keys over the mirror's control channel.
- The phone shows a pointer through the injected mouse `MotionEvent`
  (`SOURCE_MOUSE`), with no need to open the mirror window.
- It needs the Accessibility and Input Monitoring TCC grants, so it requires
  **stable signing** (see §0).

**5. Sidecar** (phone as a second display) 🟡 possible, not recommended.
- The Mac can create a display with the private `CGVirtualDisplay` API (as
  DeskPad and BetterDisplay do), stream it, and show it in a phone Activity.
- It relies on a private API that breaks across macOS releases, and a 6.5″
  panel is of little use as a second display.

**6. Continuity Camera — phone as webcam** 🟢 (blocked on signing).
- Phone side: capture with Camera2 inside the app, or with scrcpy's
  camera-source technique as `shell`. It sends HEVC over the LAN session.
- Mac side: a **CoreMediaIO Camera Extension** (`CMIOExtension`, macOS 12.3+)
  publishes "Wigly Woo Camera" to FaceTime, Zoom, Meet and other apps.
- Blocker: a camera extension is a *system extension*. It needs Developer ID
  signing, notarization, the system-extension entitlement and the app installed
  in /Applications. SwiftPM cannot build it, so it needs an Xcode project.
- A52s: 64 MP main, 12 MP ultra-wide and 32 MP front cameras. The phone does not
  advertise `camera.concurrent`, so front and back can't stream at the same
  time. Other phones that do can show both.

**7. Center Stage / Desk View / Studio Light** 🟡.
- Center Stage: Vision face tracking on the Mac crops the 4K stream.
- Desk View: the ultra-wide stream plus a perspective warp (`CIPerspectiveCorrection`).
- Lighting and portrait effects: macOS applies its own video effects to any
  camera 🔵.

**8. Continuity Camera photo / scan into Mac apps** 🟢.
- A "Take photo on phone / Scan document" menu opens a capture screen on the
  phone.
- The phone scans with ML Kit Document Scanner, which is already used for the
  QR code.
- The result lands in the Mac clipboard or is pasted into the front app.
- The system right-click "Import from iPhone" menu itself is Apple-only 🔴. We
  use our own menu bar item, a Services-menu entry and a hotkey instead.

**9. Continuity Sketch / Markup** 🟡.
- The Mac sends an image, the phone opens a simple ink canvas (Compose
  `Canvas`), and the result comes back.
- There's no Apple Pencil-grade stylus on the A52s.

**10. iPhone Mirroring** 🟢 (see Feature 1).

**11. Phone calls on Mac** 🟡.
- Doable:
  - Incoming-call banner on the Mac with caller and contact photo, using
    `TelephonyCallback` / `InCallService`-free detection.
  - Answer or decline (`TelecomManager.acceptRingingCall`/`endCall`, which needs
    the `ANSWER_PHONE_CALLS` runtime permission).
  - Dial from the Mac: register the Mac app for `tel:` URLs, and the phone
    places `ACTION_CALL`.
- **Call audio on the Mac** 🔴 for the full two-way experience:
  - Shell has `CAPTURE_AUDIO_OUTPUT`, so *listening* to the downlink via
    `VOICE_CALL_DOWNLINK` may work depending on the Qualcomm audio HAL. That is a
    spike on the A52s and often returns silence.
  - Feeding the Mac microphone *into* the cellular uplink has no API short of
    root or a system app.
  - The Apple-style alternative is the Mac acting as a Bluetooth hands-free
    unit (the way Windows Phone Link works). macOS offers third parties no
    public HFP hands-free role.
  - So the audio stays on the phone or its earbuds.

**12. SMS / Messages on Mac** 🟡.
- SMS: read with `READ_SMS` / `ContentObserver`, send with
  `SmsManager` / `SEND_SMS`. This is fine for a sideloaded or GitHub build;
  the **Play Store** only allows it for the default SMS app.
- RCS (Google Messages chats) 🔴 has no third-party API. Replies go through the
  notification RemoteInput, which is implemented.
- WhatsApp, Telegram and others: notification reply only (implemented).

**13. Security-code AutoFill** (OTP from phone on Mac) 🟢.
- Parse codes from incoming SMS and notifications.
- The Mac shows a banner, "Copy 123456", and optionally types it into the
  focused field (Accessibility).

**14. Instant Hotspot** 🟢 on your phone (spike needed).
- Shell holds `TETHER_PRIVILEGED` and `NETWORK_SETTINGS` (verified). The Shizuku
  service calls `TetheringManager.startTethering(TETHERING_WIFI)` and reads the
  SoftAP SSID and passphrase from `WifiManager.getSoftApConfiguration()`.
- The Mac joins with CoreWLAN `CWInterface.associate(to:password:)`. This fills
  `link.Hotspot` in the Go core, which is currently a stub.
- On many Android 10+ ROMs, the bare `cmd wifi start-softap` shell command is
  blocked, so we use the API, not the command.
- Samsung may still gate it (carrier hotspot entitlement check), so the fallback
  is to open the hotspot settings panel on the phone and auto-join from the Mac.

**15. Wi-Fi password sharing** 🟢.
- Mac→phone: read the current network's password from the Keychain (the user
  approves the system prompt). The phone adds it with `WifiManager.addNetwork`
  via shell (`NETWORK_SETTINGS`), or with the non-privileged
  `WifiNetworkSuggestion` as a fallback.
- Phone→Mac: `getPrivilegedConfiguredNetworks()` via shell, then CoreWLAN joins.

**16. Auto Unlock — unlock the phone from the Mac** 🟡 (security-sensitive,
opt-in). See §3 Phase 6.
- The Mac asks for Touch ID (`LAContext`) and releases the phone PIN, which is
  stored in the Keychain with `.biometryCurrentSet`.
- The PIN is sent end-to-end encrypted. The phone's Shizuku service wakes the
  screen, dismisses the keyguard and injects the PIN.
- It never works after a phone reboot, because Shizuku is dead and Android
  requires the PIN at first unlock anyway. That is correct security behaviour.
- Native alternative 🔵: **Extend Unlock → Trusted devices.** Pair the Mac over
  Bluetooth and the phone *stays* unlocked while connected, for up to 4 h.
  Android 14 only keeps it unlocked; it can't unlock a phone that's already
  locked. Mac↔phone BT links drop when idle, so this is flaky.

**17. Unlock the Mac with the phone** (like Apple Watch) 🟡.
- sudo in Terminal: a small PAM module (`pam_wigly.so`, in the style of
  `pam_watchid`) asks the phone to approve 🟢.
- Our own app's prompts: approve on the phone 🟢.
- **Mac lock screen:** technically possible with a custom
  *authorization plugin* in the `system.login.screensaver` rule (as Jamf
  Connect and Duo do). It is **not recommended**: it runs inside loginwindow, a
  bug can lock you out, and it needs admin rights.
- Login after boot with FileVault 🔴: pre-boot auth is Apple-only.
- Your M5 has Touch ID, so the value here is low.

**18. Approve with Apple Watch** (admin dialogs) 🟡. Terminal sudo via PAM
only. GUI admin dialogs would need the same authorization plugin as above.

**19. Find My — ring or locate the phone** 🟢.
- Ring the phone from the Mac at full volume, even on silent, using
  `ACCESS_NOTIFICATION_POLICY` plus the alarm stream.
- Location is optional (`ACCESS_FINE_LOCATION`), and only while reachable.
- Ring the Mac from the phone as well.
- Finding a phone that's offline or powered off 🔴: that needs Google Find My
  Device 🔵.

**20. Phone status on Mac** (battery, charging, signal, Wi-Fi, DND) 🟢.
- A menu-bar glyph and a widget. `BatteryManager` sends updates on change.

**21. Focus / Do Not Disturb sync** 🟡.
- Phone DND ↔ Mac Focus.
- Android side: `NotificationManager.setInterruptionFilter`.
- Mac side:
  - Reading is limited to a bool via `INFocusStatusCenter`.
  - Setting it has no public API. We run a user-installed Shortcut named
    "Wigly Focus" via `shortcuts run`.

**22. Now Playing / media control** 🟢.
- Phone media sessions (Spotify, YouTube Music) appear in the Mac's Now Playing
  and respond to the Mac's media keys.
- Uses `MediaSessionManager.getActiveSessions` (allowed via our
  NotificationListener) together with `MPNowPlayingInfoCenter` /
  `MPRemoteCommandCenter`.

**23. Continuity audio / AirPlay to Mac** 🟡.
- Phone audio through Mac speakers: yes, with the mirror audio pipeline in an
  "audio only" mode 🟢.
- Speaking the actual AirPlay protocol from Android 🔴 is not worth it.
- The Mac's own AirPlay receiver accepts AirPlay senders, and Android has none
  built in.

**24. Phone microphone as Mac mic** 🟡.
- The phone captures with scrcpy-style `mic` / `mic-voice-recognition`.
- The Mac exposes it with an **AudioServerPlugIn** (HAL driver in
  `/Library/Audio/Plug-Ins/HAL`). No system extension is needed, but it does
  need an admin install.

**25. Live Activities on Mac** (macOS 26+) 🟡.
- Ongoing Android notifications (deliveries, timers, navigation, uploads)
  relayed into a menu-bar "Live" strip.
- Android 16 "Live Updates" (`ProgressStyle`) would be richer, but the A52s is
  capped at Android 14. Newer phones get it for free, because the relay reads
  whatever the notification carries.

**26. Auto-send screenshots / photos** (in the spirit of Samsung's "Continue
apps") 🟢.
- A MediaStore `ContentObserver` sends new screenshots, or all new photos, to
  the Mac's inbox using the existing transfer.

**27. Lock / wake remotely** 🟢.
- Lock the Mac from the phone (`pmset displaysleepnow` with the require-password
  setting, or `SACLockScreenImmediate`).
- Wake or lock the phone from the Mac (shell `input keyevent WAKEUP/SLEEP`).

**28. Proximity** ("phone near Mac") 🟡.
- Neither device has **UWB**, so precise distance, "tap-to-share" and the
  directional finding that Apple's U1/U2 chips give are 🔴.
- Instead, the phone advertises a rotating BLE token. The Mac scans with
  CoreBluetooth and uses RSSI as a coarse *near/far* signal, which is enough to
  gate Auto Unlock and "lock Mac when I walk away".
- Phones with UWB (Pixel Pro, Galaxy S Ultra) still can't range against a Mac,
  because Macs have no UWB. So this stays 🔴 on every pair.

**29. NameDrop / tap phones** 🔴. The Mac has no NFC. The phone can read NFC
tags, but there's nothing to tap against.

**30. Transport without Wi-Fi** (AWDL, the radio AirDrop uses) 🔴.
- AWDL is Apple-proprietary, and Android can't join it.
- Wi-Fi Aware is on the phone but **not on macOS**. Wi-Fi Direct is on the phone
  but macOS has no public API for it.
- Substitutes:
  - (a) **Instant Hotspot** (Feature 14) for bulk data.
  - (b) **BLE L2CAP** channels (CoreBluetooth `openL2CAPChannel` ↔ Android
    `BluetoothDevice.createL2capChannel`, API 29+) for small control messages
    when no network is shared, at roughly 100–200 kB/s.

**31. AirPods-style buds switching** 🔴.
- Galaxy Buds auto-switch only between devices on the same Samsung account,
  such as Galaxy Books and tablets. A Mac can't join that.

**32. Passkeys / "use phone to sign in"** 🔵 already works natively.
- Safari and Chrome on the Mac show a QR code, and Android acts as the FIDO
  hybrid (caBLE) authenticator. Nothing to build; the plan just points users to
  it.

**33. iCloud Keychain / Passwords sync** 🔴.
- The Apple Passwords app and iCloud Keychain have no third-party API.
- A cross-platform manager covers this 🔵: Google Password Manager in Chrome,
  or 1Password / Bitwarden.

**34. Apple Pay on Mac approved by the phone** 🔴. Apple Pay is Apple-only, and
Google Pay has no desktop approval hook.

**35. Screen Time / Family** 🔴. Out of scope; Digital Wellbeing has no remote
API.

**36. Share Wigly "Photos" / Shared with You** 🔴. Out of scope.

---

## 3. Build plan

### Phase 0 — Spikes (1–2 days, throwaway, no merge)
1. Measure mirroring latency and quality on your pair:
   - Install `scrcpy` (brew) and connect over wireless adb: `scrcpy --video-codec=h265 -m1080 --max-fps=60`.
   - This establishes the ceiling our implementation should hit.
2. Build a minimal `MirrorServer` in a Shizuku UserService that dumps 5 s of
   HEVC to a file. This proves the virtual display and MediaCodec work as
   uid 2000 through Shizuku on One UI 6.1 (Samsung sometimes differs from AOSP
   in hidden API signatures).
3. Tethering: call `TetheringManager.startTethering` from the same UserService.
   Record whether One UI allows it.
4. Call audio: record `VOICE_CALL_DOWNLINK` for 10 s during a real call and
   check for silence.

Exit criteria: a go/no-go for each spike, written into this doc.

### Phase 1 — Mirroring MVP (≈ 2 weeks)
**Go core — add, don't change:**
- `core/session/` *(new)*:
  - Long-lived mutual-TLS session reusing `crypto.Identity` and pinned
    fingerprints from `transfer`.
  - Multiplexed channels (`video`, `audio`, `control`, `meta`) with a tiny
    framing header `{ch u8, flags u8, len u32}`. Keep it hand-rolled, or use
    `hashicorp/yamux`.
  - Channel priorities: control > audio > video. Video frames are *droppable*.
- `core/ffi`: adds `woo_session_listen()`, `woo_session_dial(peerID)` and
  `woo_session_close(id)`.
  - Each call returns a **local socketpair fd per channel**, so Swift and Kotlin
    read and write raw bytes without copying through cgo callbacks.
  - New events: `session_open` / `session_closed`.
- `core/discovery`: the beacon gains a `caps` bitfield (mirror, camera, hotspot…).
  It stays backward compatible, and old peers ignore it.
- **Unchanged:** `transfer`, `crypto`, `link` (until Phase 5), the file-transfer
  FFI and CLI.

**Android:**
- `android/app/src/main/java/com/wiglywoo/mirror/` *(new)*:
  - `MirrorServer.kt` — the Shizuku UserService entry (like
    `PrivilegedClipboardService`), plus AIDL `IPrivilegedMirror.aidl`:
    `start(fdVideo, fdAudio, fdControl, config)`, `stop()`, `setScreenPower(on)`.
  - `ScreenEncoder.kt` — virtual display via `DisplayManager` hidden API /
    `SurfaceControl`, MediaCodec HEVC→H.264 fallback, rotation handling.
    Ported from scrcpy's `ScreenEncoder`/`DisplayUtils`, keeping the Apache‑2.0
    NOTICE.
  - `AudioCapture.kt` — `AudioPlaybackCapture`/`REMOTE_SUBMIX`, Opus via MediaCodec.
  - `Controller.kt` — `InputManager.injectInputEvent` for touch, mouse, scroll
    and key; clipboard set; `expand-settings`; wake/sleep.
  - `HiddenApi.kt` — the only file allowed to use reflection, with every call
    wrapped and feature-probed.
  - `ProjectionFallback.kt` + `WiglyAccessibilityService.kt` — the
    non-Shizuku path.
- `ShizukuMirrorBridge.kt` — binds and unbinds the service (copying the
  `ShizukuClipboardBridge` pattern) and exposes `Status`.
- `CompanionManager.kt` — handles the new relay messages `mirror_request` /
  `mirror_stop`. It opens the session with `woo_session_dial`.
- `MainActivity.kt` — the Companion screen gains a "Mirroring" row
  (Shizuku ready / basic mode / off) and an active-session banner with **Stop**.
  An ongoing notification "Mac is viewing your screen · Stop" is mandatory.
- Manifest: the `FOREGROUND_SERVICE_MEDIA_PROJECTION` service type, and the
  accessibility service declaration with a config XML.

**macOS:**
- `macos/Sources/WiglyWoo/Mirror/` *(new)*:
  - `MirrorController.swift` — the session lifecycle and the state machine
    (`idle → requesting → connecting → streaming → ended/error`).
  - `VideoDecoder.swift` — `VTDecompressionSession` → `CMSampleBuffer` →
    `AVSampleBufferDisplayLayer` with `kCMSampleAttachmentKey_DisplayImmediately`.
  - `AudioPlayer.swift` — `AVAudioEngine` plus an AudioConverter for Opus.
  - `InputMapper.swift` — NSEvent → wire control messages. Covers scroll
    momentum, right-click = Back, ⌘1/⌘2 = Home/Recents, and key-code maps.
  - `MirrorWindow.swift` — a borderless titled window with a phone-shaped
    corner radius and an aspect-locked resize. Its toolbar (Home, Back,
    Recents, Control Center, Record, Screen off) follows Broadsheet tokens from
    `Theme.swift`.
  - `HotKey.swift` — Carbon global ⌘⇧M, configurable in Settings.
- `ContentView.swift` — a new sidebar destination **Phone**: the mirror
  launcher, the status of Shizuku on the phone, and recent phone apps. The
  menu-bar panel gets a "Mirror phone" row.
- `CompanionBridge.swift` — sends `mirror_request`, and handles `mirror_ready`
  and `mirror_error{reason}`.
- **Unchanged:** `CoreBridge` transfer queue, `CompanionCrypto`, `SupabaseRealtimeClient`,
  `Theme.swift` (reused only).

### Phase 2 — Mirroring polish (≈ 1–1.5 weeks)
- Audio.
- Screen-off privacy mode.
- File drop onto the window.
- Clicking a notification opens its app in the mirror.
- An app launcher with icons.
- Recording to `.mov`.
- A "Recent" drag-out shelf.
- Per-app windows at a fixed size (the virtual display, launched by display id).
  Flex resize is enabled automatically when the phone reports Android 15+.

### Phase 3 — Continuity quick wins (≈ 1.5 weeks, mostly relay messages; numbers refer to §2)
- Phone status widget (20).
- Find My ring (19).
- Now Playing (22).
- OTP AutoFill (13).
- Auto-send screenshots (26).
- Lock/wake (27).
- Handoff URLs (3).
- Focus sync (21).

New files:
- Android: `StatusReporter.kt`, `MediaBridge.kt`, `OtpDetector.kt`,
  `ScreenshotWatcher.kt`.
- Mac: `PhoneStatus.swift`, `NowPlayingBridge.swift`, `HandoffBridge.swift`.

All of them are new message types on the existing relay; no core change.

### Phase 4 — Calls & SMS (≈ 1.5 weeks)
- Android:
  - `CallBridge.kt` (`TelephonyCallback`, `TelecomManager`).
  - `SmsBridge.kt` (ContentObserver + `SmsManager`).
  - Runtime permissions onboarding rows.
- Mac:
  - A `CallBanner` window and a `Messages` sidebar destination with threads.
  - `tel:` / `sms:` URL handlers in `Info.plist`.
- Audio stays on the phone (see 11).

### Phase 5 — Instant Hotspot, Wi-Fi sharing, BLE presence (≈ 1.5 weeks)
- Go core:
  - `link.Hotspot.Resolve` is implemented for real.
  - The shells implement `Levers` through new FFI callbacks.
- Android:
  - Adds `bringUpHotspot()`, `joinWifi()` and `listSavedNetworks()` to the
    Shizuku service AIDL.
  - `BlePresence.kt` advertises a rotating HMAC token derived from the pairing
    secret.
- Mac:
  - `WifiJoiner.swift` (CoreWLAN, Location permission).
  - `BlePresence.swift` (CoreBluetooth scan with a RSSI moving average).
- Optional: a BLE L2CAP fallback control channel.

### Phase 6 — Unlock phone from Mac (≈ 1 week, opt-in, security review)
- The PIN is stored **only on the Mac**:
  - It lives in the Keychain behind
    `SecAccessControl(.biometryCurrentSet)`.
  - It's released only after a Touch ID prompt that names the phone.
  - It's sent once over the E2E-encrypted session.
  - It's zeroed from memory after injection.
- Phone side (the Shizuku service):
  - Wakes the screen and waits for the keyguard (`KeyguardManager`).
  - Injects the digits, then Enter.
  - Refuses if the phone was never unlocked since boot or if BLE presence says
    "far".
- Settings shows an explicit, persistent explanation and a kill switch. The PIN
  is off by default.
- Requires stable signing, so the Keychain ACL survives upgrades.

### Phase 7 — Universal Control (≈ 1.5 weeks, needs Developer ID)
- `EdgeController.swift`:
  - `CGEventTap` on the chosen edge; the arrangement picker is reused from
    Settings.
  - Forwards to the mirror control channel with the session in "headless" mode
    (no video).
- Android:
  - Injects a `SOURCE_MOUSE` pointer. Typing goes to the focused field through
    key injection.

### Phase 8 — Phone as webcam / mic (≈ 3 weeks, needs Developer ID + notarization)
- Build migration:
  - Move the Mac build from SwiftPM-only to an **Xcode project** (generated
    with XcodeGen, `project.yml` in the repo).
  - This is needed for the `CMIOExtension` system-extension target and its
    entitlements.
- Mac:
  - `WiglyCamera` extension with the stream, the Center Stage crop and the
    Desk View warp.
  - An optional `WiglyMic` AudioServerPlugIn.
- Android: `CameraStreamer.kt` (Camera2 → HEVC, lens picker, torch, zoom).
- Release pipeline:
  - Import the Developer ID cert into the CI keychain.
  - `codesign --options runtime`, then `notarytool submit --wait`, then
    `stapler`.
  - The brew cask stays, and the app stays in /Applications.

### Phase 9 — Stretch
- Sidecar-style display (private API).
- Continuity Sketch.
- The lock-screen auth plugin (probably never).

---

## 4. Best practices we hold to

- **Trust and privacy**
  - Every stream runs over the **existing pinned mutual-TLS identity**. The
    relay only carries small signalling messages, and those remain E2E
    encrypted with the pairing key as today.
  - No video, audio or PIN ever goes to Supabase.
  - The phone *always* shows who is watching: an ongoing notification, a status
    bar chip, and a one-tap Stop.
  - Privileged capabilities are enabled per feature, off by default when risky
    (PIN unlock, auto-type OTP, SMS).
- **Least privilege**
  - Only the Shizuku UserService runs as `shell`. It exposes a *narrow* AIDL,
    not a generic "run command".
  - The service validates its caller (`Binder.getCallingUid()` = our app).
- **Hidden API containment**
  - All reflection lives in `HiddenApi.kt`, feature-probed at startup and
    reported in capabilities.
  - When a Samsung signature differs, that feature is disabled, and nothing
    crashes.
- **Capability negotiation, not version checks**
  - Each side advertises `caps`, and the UI shows only what both ends can do.
    This covers Android 15+ flex display, concurrent cameras and Live Updates.
- **Real-time media rules**
  - Hardware codecs only.
  - Never buffer video: drop to the next keyframe on backlog.
  - The audio jitter buffer stays under 50 ms.
  - Control messages go first.
  - Measure end-to-end latency in a debug overlay.
- **Battery**
  - The mirror service exits when the session ends.
  - BLE advertising uses the low-power mode.
  - Status updates are sent on change, never polled.
- **No FLAG_SECURE / DRM bypass.** Ever. Black frames are correct.
- **Licensing.** Code ported from scrcpy keeps the Apache-2.0 headers and gets
  a `NOTICE` file. The project is MIT, which is compatible.
- **Distribution realities**
  - SMS and call-log features and the accessibility fallback are fine for GitHub
    and brew releases, but would fail Play Store policy. Keep them behind a
    build flavor (`full` vs `play`) if Play is ever a target.
- **Testing**
  - Go: `session` framing, priority and drop tests run under `-race`.
  - Android: instrumented tests for `Controller` coordinate mapping.
  - Mac: unit tests for `InputMapper` and the decoder with recorded HEVC
    fixtures.
  - Manual matrix: your A52s (Android 14 / One UI 6.1), plus an Android 15+
    Pixel emulator for flex display.
- **CI stays the source of truth.** The local Docker and CLT builds are broken,
  so every phase lands as a PR with green CI before tagging.

---

## 5. Feature table

"Your setup" = Galaxy A52s (Android 14) + MacBook M5 (macOS 27).

| # | Feature | Apple name | Status | Your setup | Other devices | Why / blocker |
|---|---|---|---|---|---|---|
| 1 | File transfer | AirDrop | ✅ Implemented | ✅ | ✅ | LAN mTLS; no AWDL, so both need a shared network or hotspot |
| 2 | Clipboard sync | Universal Clipboard | ✅ Implemented | ✅ (Shizuku for background copy) | Without Shizuku: IME/share only | Android 10+ blocks background clipboard reads for normal apps |
| 3 | Notifications on Mac, reply, dismiss | iPhone notifications on Mac | ✅ Implemented | ✅ | ✅ | — |
| 4 | Type on phone from Mac | — | ✅ Implemented | ✅ | ✅ | Requires the Wigly keyboard to be active |
| 5 | One-click phone mirroring + control | iPhone Mirroring | 🟢 Implementable | ✅ via Shizuku; basic mode without | Any Android 10+ (audio 11+) | Shizuku must be running (dies on reboot) |
| 6 | Mirror audio | iPhone Mirroring audio | 🟢 | ✅ | Android 11+ | Apps that disallow playback capture stay silent |
| 7 | Phone screen dark while mirroring | Mirroring privacy | 🟢 | ✅ | ✅ | — |
| 8 | Mirror while locked | Mirroring works locked | 🟡 Partial | Keyguard visible; use #20 | Same | Only system can bypass keyguard |
| 9 | Resizable per-app windows | macOS 27 resizable mirroring | 🟡 | Fixed-size app windows | Live resize on Android 15+ | Flex virtual display needs Android 15 APIs |
| 10 | Control Center in mirror | macOS 27 | 🟢 | ✅ | ✅ | — |
| 11 | DRM / secure apps in mirror | macOS 27 DRM playback | 🔴 Not possible | ❌ | ❌ | FLAG_SECURE and Widevine L1 surfaces are black in any capture |
| 12 | Drag files Mac → phone | Mirroring drag and drop | 🟢 | ✅ | ✅ | — |
| 13 | Drag phone → Mac | Mirroring drag and drop | 🟡 | Recent-files shelf instead | Same | Android drag payloads aren't readable cross-process |
| 14 | Record phone screen on Mac | — | 🟢 | ✅ | ✅ | — |
| 15 | Handoff (web pages) | Handoff | 🟡 | URLs both ways | Same | No API to read other apps' activity state |
| 16 | Mac cursor onto phone | Universal Control | 🟢 | ✅ | ✅ | Needs Accessibility + Input Monitoring, and Developer ID for stable grants |
| 17 | Phone as second display | Sidecar | 🟡 Not recommended | Possible | Possible | Private `CGVirtualDisplay`; tiny screen |
| 18 | Phone as webcam | Continuity Camera | 🟢 (blocked) | ✅ once signed | ✅; dual-cam only with `camera.concurrent` (not on A52s) | System extension needs Developer ID, notarization, Xcode project |
| 19 | Center Stage / Desk View | Continuity Camera effects | 🟡 | Software crop/warp | Same | No dedicated hardware; Mac does the processing |
| 20 | Unlock phone from Mac | Auto Unlock (reverse) | 🟡 Opt-in | ✅ via Shizuku + Touch ID | Same | Not after reboot (by design); security trade-off |
| 21 | Stay unlocked near Mac | Auto Unlock | 🔵 Native | Extend Unlock ↔ BT | Same | Keeps unlocked only; BT link can drop |
| 22 | Unlock Mac with phone | Unlock with Apple Watch | 🟡 | sudo + app prompts only | Same | Lock screen needs a risky auth plugin; FileVault boot is Apple-only; M5 has Touch ID |
| 23 | Approve admin with phone | Approve with Apple Watch | 🟡 | sudo only | Same | GUI admin needs an auth plugin |
| 24 | Photo / scan into Mac | Continuity Camera photo/scan | 🟢 | ✅ (own menu) | ✅ | The system "Import from iPhone" menu is Apple-only |
| 25 | Markup on phone | Continuity Sketch/Markup | 🟡 | Finger ink | Better with S Pen devices | — |
| 26 | Calls: see, answer, decline, dial | Phone on Mac / calls | 🟡 | Control ✅, audio stays on phone | Same | No public HFP hands-free role on macOS; no API to inject into the call uplink |
| 27 | SMS on Mac | Messages / Text Message Forwarding | 🟡 | SMS ✅ (sideload); RCS reply via notification | Same | RCS has no API; Play Store restricts SMS permissions |
| 28 | OTP codes on Mac | Security Code AutoFill | 🟢 | ✅ | ✅ | — |
| 29 | Turn on phone hotspot from Mac | Instant Hotspot | 🟢 (spike) | Shell has TETHER_PRIVILEGED ✅ | Varies by OEM/carrier | Samsung or carrier entitlement may block; fallback is to open the settings panel |
| 30 | Share Wi-Fi password | Wi-Fi password sharing | 🟢 | ✅ | ✅ | Mac Keychain prompt required |
| 31 | Ring / locate phone | Find My | 🟢 | ✅ while online | ✅ | Offline finding needs Google Find My Device |
| 32 | Phone battery/signal on Mac | Continuity status / widgets | 🟢 | ✅ | ✅ | — |
| 33 | Focus / DND sync | Share Across Devices (Focus) | 🟡 | Phone ✅; Mac via Shortcut | Same | macOS has no public API to set Focus |
| 34 | Phone media on Mac Now Playing | Handoff / Now Playing | 🟢 | ✅ | ✅ | — |
| 35 | Phone audio through Mac speakers | AirPlay to Mac | 🟡 | ✅ (our stream) | ✅ | Not the AirPlay protocol |
| 36 | Phone mic as Mac mic | — | 🟡 | ✅ | ✅ | Needs a HAL plugin, admin install |
| 37 | Ongoing activity on Mac | Live Activities on Mac | 🟡 | Ongoing notifications | Rich Live Updates on Android 16+ | A52s tops out at Android 14 |
| 38 | Auto-send screenshots | — | 🟢 | ✅ | ✅ | — |
| 39 | Lock/wake either device remotely | — | 🟢 | ✅ | ✅ | — |
| 40 | Proximity (near/far) | Proximity / UWB | 🟡 | BLE RSSI | Same | Coarse only |
| 41 | Precise ranging / tap-to-share | UWB, NameDrop | 🔴 | ❌ | ❌ | A52s has no UWB; no Mac has UWB or NFC |
| 42 | Link with no Wi-Fi | AWDL | 🔴 → substitute | Hotspot + BLE L2CAP | Same | AWDL is proprietary; macOS lacks Wi-Fi Aware and a Wi-Fi Direct API |
| 43 | Earbuds auto-switch | AirPods switching | 🔴 | ❌ | ❌ | Galaxy Buds switch only within the Samsung account |
| 44 | Passkeys from phone | iCloud Keychain passkeys | 🔵 Native | ✅ (QR hybrid) | ✅ | Already built into Chrome/Safari + Android |
| 45 | Password sync | iCloud Keychain | 🔴 | ❌ | ❌ | No third-party API; use a cross-platform manager |
| 46 | Pay on Mac, approve on phone | Apple Pay | 🔴 | ❌ | ❌ | Apple-only |
| 47 | Screen Time / Family | Screen Time | 🔴 | ❌ | ❌ | No remote API |

**Count:** 4 implemented · 17 implementable · 17 partial · 2 native-only · 7 not possible.

## 6. Suggested order and why
1. **Phase 0 spikes.** They remove the three biggest unknowns: One UI hidden
   APIs, tethering and call audio.
2. **Phase 1–2 mirroring.** It's the headline feature, and it builds the
   `core/session` channel that phases 5, 7 and 8 reuse.
3. **Phase 3 quick wins.** It's cheap, visible and relay-only.
4. Decide on the **Apple Developer ID** before Phase 6. Without it, every
   upgrade drops TCC grants and the webcam is impossible.
5. Phases 4 → 5 → 6 → 7 → 8, in that order.
