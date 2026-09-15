# Data contract (frozen, Phase 0)

## Note model (shared/note.ts)

`{ id, title, body, color, pinned, archived, updatedAt, deleted }`.
`updatedAt` is unix epoch ms, client-assigned. `deleted: true` = tombstone.

## Markdown in body (amendment, implemented)

`body` stays a plain string but may carry a small Markdown subset:
`**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, and `- [ ] `/`- [x] `
checklist line markers. Stored verbatim everywhere (D1, IndexedDB/Room);
each client renders it for display only (`web/components/markdown.ts` on
web; Android port follows this same syntax). No new fields, so LWW,
validation, and sync shapes are untouched: markers are just text to them.

## Drawings (amendment, implemented)

Table `drawings(id TEXT PK, strokes TEXT JSON, updatedAt INT, deleted INT,
seq INT)` via Drizzle `db/schema.ts`; dissemination cursor is a separate
`_draw_seq` counter (same delete+reinsert minting as notes). A drawing is
`{ id, strokes: [{ color, width, points: [{x, y}] }], updatedAt, deleted }`
with points normalized 0..1; caps: 200 strokes, 2000 points per stroke,
`0 < width <= 100`, color at most 32 chars. Notes attach drawings with a
full-line `![drawing](id)` marker in `body`; each client resolves the id to
strokes and paints its own canvas. Deleted notes leave orphan drawings
(no cascade in v1).

## Labels + reminders (amendment, implemented)

Table `labels(id TEXT PK, name TEXT, color TEXT default 'default',
updatedAt INT, deleted INT, seq INT)` via Drizzle `db/schema.ts`;
dissemination cursor is a separate `_label_seq` counter (same
delete+reinsert minting as notes). A label is
`{ id, name, color, updatedAt, deleted }` with name 1..120 chars and color
at most 32 chars. Notes attach labels by id through
`Note.labelIds: string[]` (at most 20 entries, each at most 64 chars,
stored as a JSON string in `notes.labelIds`); dangling ids are inert
(no cascade in v1). Reminders are `Note.reminderAt: number|null` (unix
epoch ms, `null` = none, stored in `notes.reminderAt`) with optional
`Note.repeat: "daily"|"weekly"|null` (stored in `notes.repeat`, `NULL` =
fires once); clients schedule notifications locally and advance repeating
rows on fire, the server never fires. All three fields ride note sync
under LWW with no new table or endpoint.

## Structured checklists + image attachments (amendment, implemented)

`Note.checklist: ChecklistItem[]|null` (`null` = plain text note; missing
in old rows/clients = `null`), where an item is `{ id, text, checked }`
(at most 100 items, text at most 500 chars, id at most 64 chars; stored
as a JSON string in `notes.checklist`, `NULL` = text note). Toggles ride
note sync under LWW like `labelIds` (concurrent toggles from two devices:
last write wins the whole list). Checklist-mode editors keep `body` as
the joined item lines so older clients still show the text.

`Note.attachments: Attachment[]` (missing = `[]`), where an attachment
is `{ id, name, mime, size, dataUrl, thumbUrl }`: client-downscaled
images only (`mime` must start with `image/`, both URLs are strict
`data:image/...;base64,...`), full image at most 700,000 decoded bytes,
thumbnail at most 40,000, at most 10 per note, name at most 200 chars.
Stored as a JSON string in `notes.attachments` (default `'[]'`).
Validated, never interpreted server-side; previews render `thumbUrl`,
full view renders `dataUrl`. Both fields ride note sync, export/import,
and backup with no new table or endpoint. Search tier 3 (both clients):
checklist item text and attachment file names match below the body tier.

## IndexedDB (lane B implements; interface frozen here)

- Database: `ccez-keeps`, version `2` (v1 databases upgrade in place,
  keeping notes + meta).
- Store: `notes`, keyPath `id`.
- Store: `drawings`, keyPath `id` (added v2).
- Meta keys: `syncCursor`, `pushMark`, plus `drawingsCursor`,
  `drawingsPushMark` for the drawings lane.
- Indexes: `by-updatedAt` on `updatedAt`; `by-pinned` on `pinned`
  (lane B may add more only with a plan update).
- Access via the `idb` promise wrapper (`idb` package), not raw IDB events.
- Rules: all reads/writes hit IndexedDB first; sync pushes/polls deltas in
  the background; startup renders from cache before network; every local
  delete writes a tombstone row (never a hard delete) so deletes sync;
  export/import JSON escape hatch required.

## D1 (lane C implements; schema frozen in db/schema.ts)

Table `notes(id TEXT PK, title TEXT, body TEXT, color TEXT, pinned INT,
archived INT, updatedAt INT, deleted INT, labelIds TEXT JSON default
'[]', reminderAt INT NULL, repeat TEXT NULL, checklist TEXT JSON NULL,
attachments TEXT JSON default '[]', seq INT)` via Drizzle `db/schema.ts`.
Migrations in `db/migrations/` are generated with `bun run db:generate`.
