# Goal

Build `ccez-keeps`: a faster Google Keep clone as a vanilla TS + Web Components SPA on Cloudflare, local-first, single-user, no collaboration, built only with agents. Design target is `google-keep-example.png`: dark theme, left nav (Notes / Reminders / Edit labels / Archive / Trash), top "Take a note..." composer, CSS-masonry note cards.

## Success Criteria

- First paint feels instant on repeat visits: notes render from local storage before network returns.
- Fully usable offline: create, edit, delete, search notes; syncs when back online.
- Sync works across the user's devices with last-write-wins and no duplicates.
- Auth is Cloudflare Access only: only the user can reach the app/API.
- No SSG step: plain static SPA + small Worker API in one deployment unit.
- Agent-only build: each work unit below is small enough for one agent to do and verify; parallel lanes merge without contract drift.

## Context And Current Facts

- Workspace root `/Users/c/Swe/ccez-keeps` contains only `plan.md` and `google-keep-example.png`; greenfield, no `ccez-keeps/` folder yet.
- Prior decisions carried over: no Hugo/SSG; IndexedDB local-first; Cloudflare Access only (Clerk/WorkOS rejected as overkill); Bun toolchain; D1 sync store.
- Single user, no sharing/realtime; last-write-wins + tombstones is sufficient.
- Available MCP servers: cloudflare, devtools, context7.

## Constraints And Non-goals

- Single user, no sharing, no realtime collab, no orgs/teams.
- No framework: vanilla Web Components + small store only (no Lit/React/Vue).
- No Hugo or other content-SSG in the pipeline.
- No hand-rolled passwords/OAuth sessions; Access is the gate.
- Non-goals: mobile native apps, public sharing links, extensions, AI features, import from Keep API, attachments in v1 (text + color + pin/archive only).

## Key Decisions

1. **One Worker with Static Assets, not Pages + separate Worker.** SPA (`./dist`) and API (`/api/*`) deploy as a single unit; non-API fallthrough serves `ASSETS`. One `wrangler` config, one rollback, no CORS between app and API. Rejected Pages-split because it adds a second deployment and cross-origin config for zero benefit to a single-user app.
2. **Hono for API routing inside the Worker.** Tiny, Workers-first router (`app.get/post`, `export default app`), static-asset fallthrough documented for Workers. Rejected hand-rolled `fetch` switch because route + validation + error shape drift across agents; Hono keeps the contract in 4-5 routes.
3. **Drizzle ORM over raw `D1.prepare` strings.** Typed `notes` table + generated migrations, same SQLite semantics as D1. Raw SQL strings across parallel agents drift; Drizzle schema file is the single source of truth agents import.
4. **D1 stays the sync store.** Structured queries (list/search/`updatedAt` ordering), per-user rows, Time Travel recovery. Last-write-wins on `updatedAt` + tombstones is enough with no collab.
5. **IndexedDB via the `idb` promise wrapper, not raw IDB.** Same IndexedDB API underneath, but promises instead of event/cursor boilerplate; smaller agent diffs, fewer transaction-lifetime bugs. No extra storage engine.
6. **Bun stays the toolchain.** Init/install/bundle/test from one binary; `bun test` is the unit gate.
7. **Auth: Cloudflare Access Allow policy, plus independent Worker identity check.** Access Allow over exact email (Include) with optional Require/Exclude hardening; Worker also verifies the Access JWT identity header so a misconfigured edge gate never leaves `/api/*` open.
8. **Virtualization stays conditional.** "Virtualize if >100s of notes" means only mount near-viewport cards and recycle on scroll. Below ~100 notes plain CSS-columns masonry is fine; above that shadow-root/DOM count janks. Keep plain rendering first, add virtualization only if the seeded 500-note perf test shows jank.

## Recommended Approach

Local-first SPA: Web Components render from IndexedDB (via `idb`) synchronously; a sync module debounces writes (~300ms) and pushes/pulls deltas to Hono routes on the same Worker, backed by D1 via Drizzle, all behind Access. App shell cached by Service Worker for installable PWA feel. CSS columns for masonry, lazy images, flat card DOM (no nested shadow trees per card).

Frozen contracts (Phase 0, blocks everything): `Note` model, `GET /api/notes?since=<cursor>` + `POST /api/notes/sync {upserts, tombstones, since?}` shapes, IndexedDB store interface, D1/Drizzle schema. Any contract change needs a plan update before lane agents continue.

Contract amendment (seq cursor, implemented): conflicts resolve by `updatedAt` LWW; dissemination uses a server-minted `seq` (every write deletes + reinserts to mint a fresh seq), so late-arriving old-timestamp rows stay visible. `since`/`cursor` are opaque seq values — see `contracts/api.md` "Two clocks".

Contract amendment (markdown body, implemented): `body` may carry `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `- [ ] `/`- [x] ` markers, stored verbatim and rendered per client (`web/components/markdown.ts`; Android port pending). No model, validation, or sync-shape change.

Contract amendment (drawings, implemented): new `drawings` table
(`db/schema.ts`: id PK, strokes JSON, updatedAt, deleted, seq) with its
own `_draw_seq` dissemination cursor and mirrored endpoints
(`GET /api/drawings?since`, `POST /api/drawings/sync`) under the same
dual gate. Notes reference drawings with a full-line `![drawing](id)`
marker; strokes are normalized 0..1 point lists (caps: 200 strokes,
2000 points/stroke, width 0..100). Rendered per client, never in sync.

Contract amendment (labels + reminders, implemented): new `labels` table
(`db/schema.ts`: id PK, name, color default 'default', updatedAt, deleted,
seq) with its own `_label_seq` cursor and mirrored endpoints
(`GET /api/labels?since`, `POST /api/labels/sync`) under the same dual
gate; notes carry `labelIds: string[]` (max 20 ids of max 64 chars,
referencing labels by value, no cascade) and `reminderAt: number|null`
(epoch ms, null = none, clients notify locally) — both ride note sync
under LWW with no new table or endpoint for reminders.

Schema: `notes(id TEXT PK, title TEXT, body TEXT, color TEXT, pinned INT, archived INT, updatedAt INT, deleted INT)` owned by Drizzle schema file; D1 migration generated from it.

## Work Plan

### Phase 0 — Contracts freeze (1 agent, blocks all lanes)

- **0. Contracts + scaffold.** `ccez-keeps/` Bun TS project, `wrangler` config with `assets.directory = ./dist`, Drizzle schema + migration, `Note` type, API contract doc, IndexedDB schema doc, deploy stub. No UI.
- Exit gate: `bun install`, `bun run build`, `bun test` pass; contracts committed; lanes may start.

### Wave 1 — Three parallel lanes (3 agents, start together after Phase 0)

| Lane | Agent owns | Files/surfaces | Must stub | Validation |
|---|---|---|---|---|
| A. App shell + components | `<keeps-app>`, `<note-card>`, `<note-editor>`, search/pin/archive filter, CSS masonry | `web/components/*`, `web/styles/*` | In-memory `Store` interface from Phase 0; 500-note fixture | `bun test` component tests; 500-note fixture renders, no network calls |
| B. Local-first store | `idb` wrapper, optimistic CRUD, debounced autosave, tombstones, startup-from-cache, export/import JSON | `web/store/*` | Fake sync endpoint matching `POST /api/notes/sync` contract | `bun test` store tests incl. offline-write then push shape; kill-network create/edit/search passes |
| C. Sync API | Hono routes, Drizzle+D1 queries, `since` deltas, last-write-wins, JWT identity check | `worker/*`, `db/*` | No UI; seed via `curl`/script | Worker local dev roundtrip: upsert batch, `since` pull, conflict picks newer `updatedAt`, no dupes |

Lane rules: no lane edits another lane's files; no contract edits — contract mismatch = stop and escalate to Phase 0 owner. A+B share only the frozen `Store` interface; C shares only the frozen API/Drizzle schema.

### Wave 2 — Integration (starts when A+B+C report green; 2 agents in parallel)

- **D. End-to-end sync + Access wiring (1 agent).** Needs C done + hosting contract. Lock app+API behind Access Allow (one exact email default); verify signed-out `/` and `/api/notes` deny, allowed email passes; verify Worker identity check independently of edge; run offline-create → online-sync (no dupes) and two-device edit (newer `updatedAt` wins).
- **E. Speed + PWA pass (1 agent).** Needs A+B done. Service Worker app-shell cache, lazy images, flat card DOM, CSS masonry; measure 500 seeded notes (first paint repeat-visit, editor typing latency, scroll jank); add virtualization only if measured jank.

### Wave 3 — Hardening + docs (1 agent, after D+E)

- **F. Hardening + runbook.** Empty/error/offline states, search, keyboard shortcuts, README with agent runbook (lane map, contract file pointers, deploy/rollback via prior Workers deployment, D1 Time Travel note, email-change procedure).

Dependencies: 0 before A/B/C; A+B before E; C before D (hosting contract); A+B+C before D's sync test; D+E before F. Maximum parallelism is 3 (Wave 1); never parallelize within one lane.

## Validation Plan

- `bun install`, `bun run build`, `bun test` pass from clean checkout (Phase 0 gate, repeated at F).
- Worker local dev sync roundtrip: create offline, go online, confirm no dupes; two-device edit picks newer `updatedAt` (agent C, re-run by D).
- Access check: signed-out request to `/` and `/api/notes` denied; allowed email passes; Worker identity check passes without edge (agent D).
- Perf: 500 seeded notes, measure first paint repeat-visit, typing latency, scroll jank; record before/after virtualization decision (agent E).
- PWA: installable, airplane-mode create/edit/search works, syncs on reconnect (agent E + D).
- Highest-risk check: offline-write then online-sync with conflicts — must converge, no lost newer edit (agent D, blocks F).

## Risks / Rollback

- Agent drift across lanes: frozen contracts in Phase 0; any contract change needs plan update; lane agents stop on mismatch instead of patching around it.
- Access misconfig locking user out or leaving API open: Worker identity check independent of edge gate; rollback is previous Workers deployment.
- IndexedDB schema churn: versioned migrations + export/import JSON escape hatch (lane B).
- Shadow-DOM overuse slowing list: flat card DOM; virtualize only on measured jank (lane E).
- D1 latency on sync: `since`-pulls + debounced pushes; rollback to prior deployment if sync errors spike; Time Travel for data recovery.

## Open Questions

- Confirm Access scope: one exact email address, or whole domain? Default: one exact email allowlist.
- Note images/files in v1, or text-only first? Default: text + color + pin/archive; attachments deferred.

## Sources

- https://developers.cloudflare.com/workers/static-assets/
- https://developers.cloudflare.com/d1/
- https://developers.cloudflare.com/cloudflare-one/access-controls/policies/
- https://bun.sh/docs
- https://hono.dev/docs/getting-started/cloudflare-workers
- https://orm.drizzle.team/docs/get-started/d1-new
- https://github.com/jakearchibald/idb
- https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API
- https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API
