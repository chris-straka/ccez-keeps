# API contract (frozen, Phase 0)

Base: same Worker origin as the SPA. All routes behind Cloudflare Access;
the Worker additionally checks the Access JWT identity header (lane C/D).

## GET /api/notes?since=\<cursor\>

- `since`: non-negative number, an opaque server cursor (NOT a timestamp).
  Returns rows written after it, in write order. Clients must use the
  `cursor` from the last response, never a wall-clock value.
- 400 when `since` is missing-garbage (non-numeric or negative).
- Response: `{ notes: Note[], cursor: number }`

## POST /api/notes/sync

- Request: `{ upserts: Note[], tombstones: Note[], since?: number }`
  (`tombstones` are `Note` rows with `deleted: true`; kept as a separate
  key so intent survives transport. `since` defaults to 0.)
- 400 when any upsert/tombstone fails `isNote`, when a tombstone has
  `deleted: false`, or when `since` is negative/non-numeric.
- Merge rule: per-id last-write-wins on `updatedAt`; equal timestamps
  tiebreak to the lexicographically larger JSON (see `shared/sync.ts` —
  client and server run the same rule). Intra-batch duplicate ids collapse
  by the same rule.
- Response: `{ applied: number, deltas: Note[], cursor: number }`.
  `applied` counts incoming rows that became the stored version.
  `deltas` are rows with seq greater than `since` plus any stored version
  that beat the client's copy (conflict losers always reach the client).
  Deltas include tombstones; the client applies them as deletes. The
  client stores `cursor` as its next `since`.

## Auth

Prod: every `/api/*` request must carry `Cf-Access-JWT-Assertion`,
verified RS256 against the team's certs (`worker/auth.ts`), OR
`Authorization: Bearer <device-token>` matching an unrevoked row in
`device_tokens` (plan-android.md; enrolled phones). Env
`ACCESS_TEAM_DOMAIN` + `ACCESS_AUD` (agent D sets both). 401 when both
credentials are missing/unverifiable/expired, certs unloadable, or the
device token is unknown/revoked; 403 when the Access signature is valid
but the audience is wrong. When env is unset (local dev), the Access
check is bypassed with a console warning (device-token check still runs).

Edge: the `ccez-keeps-api` Access app Bypasses `/api/*` on both hostnames
so non-browser clients can reach the Worker; the Worker gate above is the
real enforcement there. The SPA (`/`, assets) stays behind the
`ccez-keeps` Allow app. Do not remove the Bypass app without also breaking
all enrolled phones.

## Device enrollment (plan-android.md)

- `POST /api/devices/enroll {deviceName?}` — Access JWT REQUIRED (a
  device token can never mint another device). Response:
  `{deviceId, token}`; the raw token is returned exactly once and only
  its SHA-256 is stored. 400 on bad JSON or `deviceName` over 120 chars.
- `GET /api/devices` — either gate. Response: `{devices:
  [{id, deviceName, createdAt, lastSeenAt, revoked}]}`; hashes and raw
  tokens are never returned.
- `POST /api/devices/revoke {deviceId}` — either gate. 404 on unknown
  id; revoked tokens fail closed with 401 on every later call.
- `POST /api/devices/rename {deviceId, deviceName}` — either gate.
  Renames a device (phone settings, web list). 400 on missing id or
  `deviceName` over 120 chars; 404 on unknown id.
- `POST /api/devices/rotate {deviceId}` — either gate. Mints a fresh raw
  token (`{deviceId, token}`, same shape as enroll) and replaces the
  stored hash; the old token 401s immediately. 404 on unknown or revoked
  ids.
- `GET /api/devices/code?to=keeps://...` — Access JWT REQUIRED. Mints a
  single-use enrollment code (10-minute TTL, hash stored only) and 302s to
  the `to` callback with `?code=<raw>` appended. 400 unless `to` starts
  with `keeps://`. Exists because a Custom Tab cannot hand the Access JWT
  to the app (httpOnly browser cookie).
- `POST /api/devices/exchange {code, deviceName?}` — NO auth (the unused,
  unexpired code is the credential). Response: `{deviceId, token}` like
  enroll. 404 on unknown/used codes, 410 on expired ones.

## Drawings (plan.md amendment)

Mirror of the notes endpoints with an independent `seq` cursor, same dual
gate. Shapes: `Drawing { id, strokes, updatedAt, deleted }` (caps in
`contracts/data.md`).

- `GET /api/drawings?since=<seq>` -> `{drawings, cursor}`. 400 on bad
  `since`.
- `POST /api/drawings/sync {upserts, tombstones, since?}` ->
  `{applied, deltas, cursor}`. 400 on malformed bodies or invalid
  drawings. Conflict rule and cursor semantics identical to notes.

## Labels (plan.md amendment)

Mirror of the notes endpoints with an independent `seq` cursor, same dual
gate. Shapes: `Label { id, name, color, updatedAt, deleted }` (caps in
`contracts/data.md`). No endpoint exists for reminders: `reminderAt` rides
the notes lane (below) and clients schedule notifications locally.

- `GET /api/labels?since=<seq>` -> `{labels, cursor}`. 400 on bad
  `since`.
- `POST /api/labels/sync {upserts, tombstones, since?}` ->
  `{applied, deltas, cursor}`. 400 on malformed bodies or invalid
  labels. Conflict rule and cursor semantics identical to notes.

## Note label + reminder fields (plan.md amendment)

Notes carry `labelIds: string[]` (at most 20 ids, each at most 64 chars,
referencing `Label` ids by value — no server-side join or cascade),
`reminderAt: number|null` (unix epoch ms fire time, `null` = none), and
`repeat: "daily"|"weekly"|null` (`null` = fires once; missing on input is
accepted as `null` for pre-repeat clients). All three ride note sync free
under the same per-note LWW and are validated by `isNote` (400 on
violation, like any other malformed note).

## Conflict rule (both sides, identical)

Newer `updatedAt` wins; equal timestamps → lexicographically larger JSON
wins. A newer tombstone deletes; an older tombstone loses to a newer live
edit. No duplicates: merge is keyed by `id`.

## Two clocks

`updatedAt` (client wall clock) decides conflicts only. Dissemination uses
`seq`, a server counter: every write deletes + reinserts the row to mint a
fresh seq, so a late-arriving old-timestamp row is still newer than every
outstanding cursor and can never go unseen. `since`/`cursor` are seq
values; clients treat them as opaque.
