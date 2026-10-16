# Mac app (`desktop/`)

Owner, 2026-10-14: "I don't like how closing my browser closes this app as
well." The PWA lives inside Brave, so it quits with it. The Mac app is
Keeps on its own: own Dock icon and window, own process, own storage. The
PWA stays as it is.

## What it is

A Tauri 2 shell (`desktop/`, same packaging, signing, updater and release
pattern as ccez-llm's `src-tauri/`) around the same web build
(`bun run build` → `dist/`), bundled into the app:

- **Offline like the PWA.** The shell ships inside the app, so it opens
  with no network; notes live in IndexedDB in the app's own WebKit store
  (`~/Library/WebKit/uk.ccez.keeps`). No Service Worker is needed or
  registered.
- **Sync with a device token.** `web/desktop.ts` swaps `fetch` so every
  `/api/*` call goes to the app's `api_fetch` command
  (`desktop/src/api.rs`), which sends it to keeps.ccez.uk with
  `Authorization: Bearer <device token>`. The page never sees the token,
  and nothing is cross-origin, so the Worker needs no CORS and no change.
- **Sign-in like Android.** "Sign in with your browser" (the signed-out
  banner, or Keeps → Sign In with Browser…) opens
  `/api/devices/code?to=keeps://enroll`; Access logs the browser in, the
  Worker 302s to `keeps://enroll?code=…`, and the app trades the code at
  `/api/devices/exchange` for its own token. The browser can close right
  after; it is never needed again. The device shows in the web Devices
  panel as "Mac app on <computer name>"; revoke it there or with
  Keeps → Sign Out (revokes, then forgets the token; notes stay local).
- **Token storage.** `~/Library/Application Support/uk.ccez.keeps/device.json`,
  mode 600, same idea as the `keeps` CLI's token file. Not the Keychain:
  the app is ad-hoc signed, so every update would change its signature and
  re-prompt for Keychain access.
- **Lives in the Dock.** Closing the window hides it; the app and its sync
  keep running, and clicking the Dock icon brings it back. Cmd+Q quits.
  Size, position and maximized state are remembered
  (tauri-plugin-window-state).
- **Updates itself.** At launch and every 6 h it reads
  `releases/latest/download/latest.json`, checks the signature against
  `plugins.updater.pubkey`, installs over itself, and relaunches into the
  Dock (window closed) as soon as the window is closed, so it never
  interrupts typing. Keeps → Check for Updates does the same on demand.

## Build and run

```sh
bun install
bunx tauri build --bundles app        # desktop/target/release/bundle/macos/Keeps.app
cd desktop && cargo test              # sign-in link parsing, API path guard, token file mode
```

Fleet jobs build with `CARGO_TARGET_DIR` set, so the bundle lands under
that folder instead. `createUpdaterArtifacts` needs the signing key in the
environment for a full build:
`TAURI_SIGNING_PRIVATE_KEY="$(cat ~/.tauri/keeps.key)"
TAURI_SIGNING_PRIVATE_KEY_PASSWORD="$(cat ~/.tauri/keeps.key.password)"`
(both on y8; the same pair is in the repo's Actions secrets).

Environment knobs: `KEEPS_BASE_URL` (another Worker, e.g. `wrangler dev`),
`KEEPS_NO_UPDATE` (no self-update), `KEEPS_SNAPSHOT=/path.png` (+
`KEEPS_SNAPSHOT_DELAY` seconds, `KEEPS_SNAPSHOT_QUIT`): the app writes a
PNG of its own page with WKWebView's snapshot, which needs no Screen
Recording permission and works with the display asleep, so a fleet job
can prove what the window shows.

## Release

Push a `v*` tag (release.yml): the APK leg and two Mac legs (Apple
silicon, Intel) build in parallel and the tail job attaches
`Keeps-macos-{apple-silicon,intel}.dmg`, the `.app.tar.gz` + `.sig`
updater tarballs and `latest.json` next to the APK. The tag is the
version (`tauri build --config '{"version": …}'`).

## First install on a Mac

From the release page, open the `.dmg` and drag Keeps to Applications.
The app is ad-hoc signed (no Apple account), so a downloaded copy needs
right-click → Open once, or
`xattr -d com.apple.quarantine /Applications/Keeps.app` if macOS calls it
damaged. Copies installed with `curl`/`cp` carry no quarantine flag and
open straight away. Updates after that are silent.

## Verifying headless (how 2026-10-16 was checked on z)

A fleet job on z (no ssh, no Screen Recording): mint a single-use enroll
code into D1 (`wrangler d1 execute ccez-keeps --remote`, SHA-256 of the
code into `enroll_codes`), launch the app with `KEEPS_SNAPSHOT`, then
`open "keeps://enroll?code=<code>"`. That runs the real deep link and
exchange; only the browser's Access login is skipped. The snapshot shows
the synced notes. Revoke the test device afterwards in the Devices panel.
