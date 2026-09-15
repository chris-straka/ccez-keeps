# Plan: Android app (native Kotlin, device-token auth)

## Goal

Ship a native Android notes app for `ccez-keeps` in `android/` (sibling to
`web/`): fast cold start, Room-backed offline-first list, background sync to
the existing Worker API, authenticated by a per-device token. Web app behind
Cloudflare Access stays untouched.

## Success criteria

- Cold start to visible notes from local Room DB feels instant; no browser
  involved after first launch.
- Fully usable offline: create, edit, delete, search; syncs when back online
  via WorkManager.
- Sync converges with the web app: same frozen API shapes, same LWW rule, no
  duplicates.
- One phone enrolls itself via a single login; token is long-lived,
  revocable per device; revoking one phone does not affect web or others.
- `bun test` plus Android unit tests pass; contract files unchanged in shape.

## Context and current facts

- API: `GET /api/notes?since=<cursor>` + `POST /api/notes/sync
  {upserts, tombstones, since?}` frozen in `contracts/api.md`; `Note` shape
  frozen in `shared/note.ts`; conflict rule frozen in `shared/sync.ts`
  (LWW on `updatedAt`, tiebreak lexicographically larger JSON).
- Server: single Hono Worker (`worker/index.ts`) + D1 via Drizzle
  (`db/schema.ts`, `worker/notes.ts`); dissemination cursor is server `seq`,
  conflicts decided by client `updatedAt` ("two clocks").
- Auth today: every `/api/*` requires `Cf-Access-JWT-Assertion` verified
  RS256 (`worker/auth.ts`), enforced in `worker/index.ts`. Local dev bypasses
  when env is unset.
- Prior decision this session: no KMP. The shareable logic is ~30 lines
  (`pickWinner`, `mergeNoteLists`); port to Kotlin with parity tests instead.
- Prior decision: flow 2 wins (own device token, flow 1 rejected). Phone
  enrolls itself on-device; no desktop step.

## Decisions

1. **Device tokens, not Cloudflare JWTs, for the phone.** The Worker accepts
   either a valid Access JWT (web, as today) or
   `Authorization: Bearer <device-token>` matching an unrevoked hash
   (phone). Rejected flow 1 (reusing Access JWTs per sync) because
   Cloudflare session expiry strands background sync.
2. **Self-enrollment via system browser (Custom Tab), once.** First launch
   opens the Access login URL in a Chrome Custom Tab; the app exchanges the
   resulting Access JWT for a device token via `POST /api/devices/enroll`,
   stores the device token in EncryptedSharedPreferences/Keystore, and
   discards the Access JWT. No typed codes, no desktop needed.
3. **`android/` as sibling to `web/`.** Layout: `android/app/` (UI, Room,
   sync worker) + `android/synclogic/` (pure-Kotlin LWW port + API DTOs).
   No edits to `web/`, `shared/`, or `contracts/` shapes.
4. **Raw tokens never stored server-side.** D1 holds SHA-256 hashes plus
   metadata; the raw token is returned exactly once at enroll time.

## Approach

### Server (Worker + D1)

- New table `device_tokens` in `db/schema.ts` (migration via
  `bun run db:generate`):
  `tokenHash TEXT PK, deviceName TEXT, createdAt INT, lastSeenAt INT,
  revoked INT default 0`. Follows the existing Drizzle single-source pattern.
- New routes (auth: Access JWT required, same `checkAccess` gate):
  - `POST /api/devices/enroll {deviceName}` -> `{deviceId, token}`
    (raw token returned once; store hash only).
  - `GET /api/devices` -> list (id, name, created/lastSeen, revoked;
    never hashes).
  - `POST /api/devices/revoke {deviceId}` -> ok.
- Middleware change (`worker/index.ts`, `/api/*` gate): accept when either
  `checkAccess` passes (web) or the Bearer token's SHA-256 matches an
  unrevoked row (phone; update `lastSeenAt`). Fail closed 401 otherwise;
  keep the 403-for-wrong-audience semantics for the Access path.
- No change to `applySync`/`getDeltas` or the sync contract; device auth is
  a gate, not a sync semantic.

### Android app

- First launch: Custom Tab to the Access login URL -> capture Access JWT ->
  `POST /api/devices/enroll` -> persist device token (EncryptedShared
  Preferences / Keystore) -> discard Access JWT. Show device name field
  (default: phone model).
- Sync engine (OkHttp + Room + WorkManager): interceptor attaches
  `Authorization: Bearer ...` to the two frozen sync calls with identical
  JSON shapes; push-then-pull with debounce (~300ms) and retry on reconnect,
  mirroring `web/store/sync.ts` semantics (dirty-by-`updatedAt`,
  cursor watermark, tombstones as deletes, `deleteForever` only online).
- LWW port: `pickWinner`/`mergeNoteLists` reimplemented in pure Kotlin in
  `android/synclogic/`, validated by parity tests using the same vectors as
  `tests/sync.test.ts`.
- Drawings (implemented 2026-09-14): Room `drawings` table (DB v2,
  `MIGRATION_1_2`), own watermarks, mirrored push/pull lane in `SyncEngine`,
  `Drawing.kt` LWW port with JS-canonical tiebreak JSON (whole doubles print
  bare so both platforms pick the same winner), freehand editor +
  card thumbnails at web scale (`width*W/600`, 3:2). Verified: `:synclogic`
  tests + 3 on-emulator Compose tests (thumbnail, placeholder, draw→marker
  round trip). Requires test-runner 1.7.0 family (1.6.2 crashes on API 36).
- Settings: device name, sign out (wipe local token), link to web device
  list for revoke.

## Work plan (status: 1-4 built and verified on emulator + prod 2026-09-14)

1. **Server auth + table — DONE.** Schema, migrations 0002/0003 (applied
   remote via MCP), enroll/list/revoke + code/exchange routes, dual-gate
   middleware, 13 new tests. Deployed.
2. **Android shell + local store — DONE.** `android/` scaffold
   (`:synclogic` + `:app`), Room mirror of `Note`, composer/editor UI,
   offline CRUD. Debug APK assembles.
3. **Android sync — DONE.** DTOs, LWW port + 12 parity tests, OkHttp
   client, WorkManager push-then-pull. Verified end-to-end: emulator
   created a note, row landed in prod D1.
4. **Enrollment UX — DONE.** Custom Tab login -> code bridge
   (`GET /api/devices/code` 302 to `keeps://enroll`) -> exchange ->
   EncryptedSharedPreferences; error/expired states; settings + sign out.
   Requires the `ccez-keeps-api` Bypass app on `/api/*` (both hostnames),
   else the edge 302s all phone traffic to login.
5. **Hardening + docs — DONE 2026-09-15 except console submission.**
   Rename (`POST /api/devices/rename`) + rotation
   (`POST /api/devices/rotate`) server routes with tests, Android
   settings UI (rename field, New login button), release signing via
   env keystore (unsigned machines fall back to debug-signed release),
   motion + haptics on both clients, adaptive launcher icon, full
   `android/store/` assets (listing copy, 512 icon, feature graphic,
   4 real emulator screenshots). Left: the store submission clicks
   (upload AAB + assets, data safety, rating) — checklist in
   `android/store/README.md`.
6. **Post-wave fixes (verified on emulator 2026-09-15).** Pulled notes
   with reminders now schedule their firing in `applyDeltas` (pure
   `planReminders` in `:synclogic` + tests); the widget's compose
   intent opens a real blank composer (hoisted composer state);
   widget tap → composer and reminder pull → notification both
   proven on-device.
6. **Drawings parity — DONE 2026-09-14.** Server routes + D1 migration 0004
   deployed; web canvas dialog + card render + sync lane (100 `bun` tests,
   `tsc` clean); Android lane + UI as above, verified on emulator.

## Validation

- `bun install`, `bun run build`, `bun test` pass; Android unit tests pass
  (LWW parity, DTO shapes, interceptor header).
- Enroll on phone emulator, create note on web, confirm phone pulls it;
  offline phone edit wins/loses correctly against web by newer `updatedAt`.
- Revoke the phone: its next sync 401s; web and other devices unaffected.
- Airplane-mode create/edit/search on phone works; syncs on reconnect with
  no duplicates.
- No browser opens after first launch (verify via fresh install + background-sync trigger).


## Risks / rollback

- Token leak: hashes only server-side; revoke path tested before release;
  rollback is prior Workers deployment + D1 Time Travel note from README.
- Custom Tab capture edge cases (redirect/cancel/expired Access JWT):
  explicit error states, retry without losing local notes.
- Clock skew across devices: LWW inherits the web app's wall-clock
  assumption; acceptable for single user, same as today.
- Scope creep into KMP/iOS: deferred; the Kotlin port is intentionally
  throwaway-compatible with a future shared module.

## Open questions

- Access session lifetime to assume during the one-time enroll call?
  (Default: whatever Zero Trust sets today; enroll retries on expiry.)
- Device-name default and rename UX: editable on phone, on web, or both?
  Default: editable on phone, read-only on web list.
