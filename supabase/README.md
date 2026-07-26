# Auth-free Supabase testing setup

This test transport uses **Supabase Realtime Broadcast only**. It does not need
database tables, SQL migrations, Edge Functions, user accounts, or Row Level
Security yet. Messages are encrypted on the sender before they enter Supabase.

## 1. Create the project

1. Create a Supabase project at <https://supabase.com/dashboard>.
2. Open **Project Settings → API** (or the project's **Connect** dialog).
3. Copy the **Project URL**.
4. Copy the client-safe **Publishable key**. An older `anon` key also works.
   Never put a `service_role` or secret key in either app.
5. In **Realtime Settings**, keep public channel access enabled for this
   auth-free test. Authentication and private-channel RLS will replace this in
   the production phase.

## 2. Configure the Mac

1. Open `macos/dist/WiglyWoo.app`.
2. Select the gear button.
3. Paste the Project URL and Publishable/anon key.
4. Select **Generate** beside Pairing Secret, then **Copy**.
5. Enable **Keep remote companion online**, then select **Done**.

## 3. Configure Android

1. Install `android/app/build/outputs/apk/debug/app-debug.apk`.
2. Open wigly-woo and select **Set up** or **Manage**.
3. Paste the same URL, key, and pairing secret.
4. Enable the remote connection, notifications, and clipboard.
5. Open **Notification access** and allow wigly-woo.
6. Open **Enable keyboard**, enable the wigly-woo keyboard, and select it when
   typing into an Android text field.
7. Save. Android shows a low-priority ongoing notification while the remote
   companion connection is active.

The status pill should change to **Remote online** on the Mac and **Online** on
Android. The phone and Mac may now be on unrelated Wi-Fi/mobile networks.

## Security boundary in testing mode

- Use the generated 64-character secret; do not choose a short phrase.
- The secret derives an unguessable channel name and an AES-256-GCM key.
- Supabase receives ciphertext, sender ID, timestamps, and connection metadata.
- There is no offline queue in this auth-free version. Events sent while the
  other device is offline are not replayed.
- Replacing the public channel with Supabase Auth, private channels, RLS, device
  revocation, and an encrypted offline queue is required before public release.
