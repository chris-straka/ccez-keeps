# ccez-keeps

A fast Google Keep clone: vanilla TS + Web Components SPA, local-first
(IndexedDB), syncing to Cloudflare Workers + D1, gated by Cloudflare Access.
Single user, no collaboration.

## Stack

| Layer | Choice |
|---|---|
| UI | Vanilla Web Components, light DOM, CSS-columns masonry |
| Local store | IndexedDB via `idb` (`web/store/idb-store.ts`) |
| Sync | Debounced push/pull in `web/store/sync.ts`, LWW on `updatedAt` |
| API | Hono on a single Worker (`worker/`), Drizzle + D1 |
| Toolchain | Bun (`bun install`, `bun run build`, `bun test`, `bunx tsc --noEmit`) |
| Deploy | `wrangler deploy` (Worker + static assets, one unit) |
| Auth | Cloudflare Access Allow (exact email), plus Worker JWT check |

## Map

- `shared/` — frozen data contract: `Note`, validators, LWW merge
- `contracts/` — frozen API + data docs (lanes build against these)
- `web/components/` — `<keeps-app>`, `<note-card>`, `<note-editor>`
- `web/store/` — `Store` interface, IndexedDB + memory backends, sync engine
- `worker/` — Hono routes, Drizzle queries, Access JWT verification
- `db/` — Drizzle schema + generated migrations
- `tests/` — bun tests (see table below)

## Test layout

| File | Covers |
|---|---|
| `tests/sync.test.ts` | LWW merge, tiebreaks, `since` cursor |
| `tests/worker-sync.test.ts` | API routes via HTTP against in-memory SQLite |
| `tests/access-jwt.test.ts` | Access JWT verify (generated keypair, no network) |
| `tests/store.test.ts` | Store contract on both backends |
| `tests/sync-engine.test.ts` | Push shape, conflicts, debounce, 401, delete-forever |
| `tests/components.test.ts` | Shell render/search/mutations, 500-note smoke |
| `tests/devices.test.ts` | Devices API client (list/rename/revoke shapes) |
| `tests/components.test.ts` (devices panel) | Web device list UI: render, rename, revoke, error state |

## Local development

```sh
bun install
bun run build        # -> dist/
bun test
wrangler d1 migrations apply DB --local
wrangler dev --local --port 8787
```

Remote D1 (already migrated for the initial schema):

```sh
wrangler d1 execute ccez-keeps --remote --file=db/migrations/0000_*.sql
# later changes:
bun run db:generate  # then apply the new file --local and --remote
```

## Deploy / rollback

Live: https://keeps.cstraka.dev (also `ccez-keeps.chris-e69.workers.dev`).
Both hostnames sit behind the `ccez-keeps` Access app (worker destination);
allowlist: exact email `skylake112@outlook.com` via the `owner-only` policy.
`/api/*` on both hostnames is Bypassed by the `ccez-keeps-api` Access app
so the Android app can reach the Worker; the Worker verifies every API
call itself (Access JWT or device credential), so the Bypass opens no
unauthenticated surface.

```sh
wrangler deploy
wrangler deployments list   # rollback target
wrangler rollback <deployment-id>
```

D1 point-in-time recovery: D1 Time Travel in the dashboard
(`ccez-keeps` database). IndexedDB escape hatch: the app has no UI for it
yet — export via DevTools console with the store's `exportJson()`.

## Access

- Allowlist default: exact email `skylake112@outlook.com` (change in the
  Zero Trust Access app policy for the app hostname).
- Worker env `ACCESS_TEAM_DOMAIN` + `ACCESS_AUD` enforce the JWT check
  independently of the edge gate (`worker/auth.ts`). When unset (local
  dev), the check bypasses with a console warning — never deploy to prod
  without them set.
- Verify: signed-out `curl https://<host>/` and `/api/notes` must not
  return notes (Access login redirect / 403).

## Android app (`android/`)

Native Kotlin app, same sync contract. See [plan-android.md](plan-android.md).

- Modules: `:synclogic` (pure Kotlin: `Note`, LWW port, API DTOs — parity
  tested against `tests/sync.test.ts`) and `:app` (Compose UI, Room,
  OkHttp, WorkManager, EncryptedSharedPreferences token store).
- Auth: per-device Bearer token. First launch opens the Access login in a
  Custom Tab; the server 302s to `keeps://enroll?code=...` and the app
  exchanges it via `POST /api/devices/exchange`. Browser never opens again.
- New server routes: `POST /api/devices/enroll`, `GET /api/devices`,
  `POST /api/devices/revoke`, `POST /api/devices/rename`,
  `POST /api/devices/rotate`, `GET /api/devices/code`,
  `POST /api/devices/exchange` (see `contracts/api.md`).
- Motion + haptics: Compose `AnimatedContent`/`animateItem`/FAB
  scale-fade plus `HapticFeedbackType` ticks on toggles, trash, saves,
  and settings; web `buzz()` vibration on the same actions
  (`web/components/haptics.ts`, silent where unsupported).
- Build/test: `cd android && ./gradlew :app:assembleDebug :synclogic:test`
  (needs JDK 17+ and the Android SDK; APK at
  `app/build/outputs/apk/debug/app-debug.apk`).
- Release signing (keys never committed):
  `KEEPS_KEYSTORE_PATH`, `KEEPS_KEYSTORE_PASSWORD`, `KEEPS_KEY_ALIAS`,
  `KEEPS_KEY_PASSWORD`, then `./gradlew :app:bundleRelease`. Without
  them the release build is debug-signed for side-loading.
- Play Store assets live in `android/store/` (listing copy, 512 icon,
  feature graphic, 4 real emulator screenshots, console checklist).

## PWA / perf

- `web/sw.js` caches the app shell; `/api/*` always bypasses the cache.
- 500-note render smoke lives in `tests/components.test.ts` (~50ms
  measured; virtualization stays deferred unless this regresses).
- Install icons: `web/icon-192.png`, `web/icon-512.png` (rendered from
  `web/icon.svg`).
