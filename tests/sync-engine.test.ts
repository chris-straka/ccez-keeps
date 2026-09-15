// Lane B regression suite: SyncEngine against a fake server implementing
// contracts/api.md shapes — including the seq cursor. Offline-write-then-
// push, conflict convergence, late-arrival visibility, debounce
// coalescing, and the 401 path — no network.
import { describe, expect, test } from "bun:test";
import type { Drawing } from "../shared/drawing.js";
import { newNote, type Note } from "../shared/note.js";
import { MemoryStore } from "../web/store/memory-store.js";
import { SyncEngine } from "../web/store/sync.js";
import type { SyncStatus } from "../web/store/types.js";

const note = (overrides: Partial<Note> & { id: string }) =>
  newNote({ title: "t", body: "b", updatedAt: 1000, ...overrides });

interface Recorded {
  method: string;
  path: string;
  body?: unknown;
}

interface PushSent {
  upserts: Note[];
  tombstones: Note[];
  since: number;
}

/**
 * In-memory fake of the Worker sync API: LWW on updatedAt for conflicts,
 * server-minted seq for dissemination, opaque cursor in every response.
 */
function makeServer(seed: Note[] = []) {
  const rows = new Map<string, { note: Note; seq: number }>();
  let seq = 0;
  const requests: Recorded[] = [];
  const store = (n: Note) => {
    seq += 1;
    rows.set(n.id, { note: n, seq });
  };
  for (const n of seed) store(n);

  const deltasSince = (since: number) =>
    [...rows.values()]
      .filter((r) => r.seq > since)
      .sort((a, b) => a.seq - b.seq)
      .map((r) => r.note);

  const fetchFn = (async (url: string, init?: RequestInit) => {
    const u = new URL(url, "http://x");
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    requests.push({ method: init?.method ?? "GET", path: u.pathname + u.search, body });
    if (u.pathname === "/api/notes" && (init?.method ?? "GET") === "GET") {
      const since = Number(u.searchParams.get("since") ?? 0);
      return Response.json({ notes: deltasSince(since), cursor: seq });
    }
    if (u.pathname === "/api/notes/sync" && init?.method === "POST") {
      const { upserts = [], tombstones = [], since = 0 } = (body ?? {}) as Partial<PushSent>;
      let applied = 0;
      const serverWins: Note[] = [];
      for (const n of [...(upserts ?? []), ...(tombstones ?? [])]) {
        const cur = rows.get(n.id);
        // Mirrors pickWinner: newer updatedAt wins; ties break to the
        // lexicographically larger JSON.
        const wins =
          !cur ||
          n.updatedAt > cur.note.updatedAt ||
          (n.updatedAt === cur.note.updatedAt &&
            JSON.stringify(n) >= JSON.stringify(cur.note));
        if (wins) {
          store(n);
          applied += 1;
        } else {
          serverWins.push(cur.note);
        }
      }
      // Same stored version can appear in both lists; dedupe by id.
      const seen = new Set<string>();
      const deltas = [...deltasSince(since ?? 0), ...serverWins].filter((n) =>
        seen.has(n.id) ? false : (seen.add(n.id), true),
      );
      return Response.json({ applied, deltas, cursor: seq });
    }
    return new Response("not found", { status: 404 });
  }) as unknown as typeof fetch;

  // A second client writing directly (bypasses cursors, like a device).
  const pushDirect = (n: Note) => {
    const cur = rows.get(n.id);
    if (!cur || n.updatedAt >= cur.note.updatedAt) store(n);
  };
  const snapshot = () => new Map([...rows].map(([id, r]) => [id, r.note]));
  return { rows: snapshot, requests, fetchFn, pushDirect, seq: () => seq };
}

describe("SyncEngine", () => {
  test("offline write flushes with the contract push shape; second flush is quiet", async () => {
    const { requests, fetchFn } = makeServer();
    const store = new MemoryStore();
    const engine = new SyncEngine(store, { fetchFn, online: () => true });
    await store.put(note({ id: "1", body: "offline edit", updatedAt: 100 }));
    await engine.flush();

    const push = requests.find((r) => r.path === "/api/notes/sync");
    expect(push?.method).toBe("POST");
    const sent = push?.body as PushSent;
    expect(sent.upserts.map((n) => n.id)).toEqual(["1"]);
    expect(sent.tombstones).toEqual([]);
    expect(typeof sent.since).toBe("number");
    expect(await store.getCursor()).toBe(1);

    const before = requests.length;
    await engine.flush();
    expect(requests.length).toBe(before + 1); // pull only, no push
    expect(requests[before]?.path).toMatch(/^\/api\/notes\?since=/);
    engine.destroy();
  });

  test("server-newer conflict converges locally", async () => {
    const server = makeServer([note({ id: "1", body: "server", updatedAt: 200 })]);
    const store = new MemoryStore([note({ id: "1", body: "local", updatedAt: 100 })]);
    const engine = new SyncEngine(store, { fetchFn: server.fetchFn, online: () => true });
    await engine.flush();
    expect((await store.get("1"))?.body).toBe("server");
    expect(await store.getCursor()).toBe(server.seq());
    engine.destroy();
  });

  test("cursor is an opaque seq, not a timestamp", async () => {
    const server = makeServer([note({ id: "9", updatedAt: 500 })]);
    const store = new MemoryStore();
    const engine = new SyncEngine(store, { fetchFn: server.fetchFn, online: () => true });
    await engine.pull();
    expect((await store.get("9"))?.id).toBe("9");
    expect(await store.getCursor()).toBe(1);
    engine.destroy();
  });

  test("late-arriving old-timestamp row still reaches the client", async () => {
    const server = makeServer([note({ id: "old", body: "first", updatedAt: 9000 })]);
    const store = new MemoryStore();
    const engine = new SyncEngine(store, { fetchFn: server.fetchFn, online: () => true });
    await engine.pull();
    expect(await store.getCursor()).toBe(1);
    // Another device's offline write lands with an older timestamp AFTER
    // our cursor already passed it. Seq dissemination must still deliver it.
    server.pushDirect(note({ id: "late", body: "offline write", updatedAt: 100 }));
    await engine.pull();
    expect((await store.get("late"))?.body).toBe("offline write");
    expect(await store.getCursor()).toBe(2);
    engine.destroy();
  });

  test("401 surfaces error status and advances nothing", async () => {
    const statuses: SyncStatus[] = [];
    const store = new MemoryStore([note({ id: "1", updatedAt: 100 })]);
    const engine = new SyncEngine(store, {
      fetchFn: (async () =>
        new Response("denied", { status: 401 })) as unknown as typeof fetch,
      online: () => true,
      onStatus: (s) => statuses.push(s),
    });
    await engine.flush();
    expect(statuses).toContain("error");
    expect(await store.getCursor()).toBe(0);
    engine.destroy();
  });

  test("schedulePush debounces to a single push", async () => {
    const { requests, fetchFn } = makeServer();
    const store = new MemoryStore([note({ id: "1", updatedAt: 100 })]);
    const engine = new SyncEngine(store, { fetchFn, online: () => true, debounceMs: 5 });
    engine.schedulePush();
    engine.schedulePush();
    engine.schedulePush();
    await new Promise((r) => setTimeout(r, 60));
    await engine.flush(); // settle; must not push again
    expect(requests.filter((r) => r.method === "POST").length).toBe(1);
    engine.destroy();
  });

  test("drawings lane pushes local strokes and pulls foreign ones", async () => {
    const rows = new Map<string, { d: Drawing; seq: number }>();
    let seq = 0;
    const requests: string[] = [];
    const fetchFn = (async (url: string, init?: RequestInit) => {
      const u = new URL(url, "http://x");
      requests.push(`${init?.method ?? "GET"} ${u.pathname}`);
      const body = init?.body ? (JSON.parse(init.body as string) as Record<string, unknown>) : {};
      if (u.pathname === "/api/notes") return Response.json({ notes: [], cursor: 0 });
      if (u.pathname === "/api/notes/sync") {
        return Response.json({ applied: 0, deltas: [], cursor: 0 });
      }
      if (u.pathname === "/api/drawings") {
        const since = Number(u.searchParams.get("since") ?? 0);
        const ds = [...rows.values()]
          .filter((r) => r.seq > since)
          .sort((a, b) => a.seq - b.seq)
          .map((r) => r.d);
        return Response.json({ drawings: ds, cursor: seq });
      }
      if (u.pathname === "/api/drawings/sync") {
        const upserts = (body["upserts"] ?? []) as Drawing[];
        const tombstones = (body["tombstones"] ?? []) as Drawing[];
        const since = (body["since"] ?? 0) as number;
        let applied = 0;
        for (const d of [...upserts, ...tombstones]) {
          const cur = rows.get(d.id);
          if (!cur || d.updatedAt >= cur.d.updatedAt) {
            seq += 1;
            rows.set(d.id, { d, seq });
            applied += 1;
          }
        }
        const ds = [...rows.values()].filter((r) => r.seq > since).map((r) => r.d);
        return Response.json({ applied, deltas: ds, cursor: seq });
      }
      return new Response("not found", { status: 404 });
    }) as unknown as typeof fetch;

    const stroke = { color: "white", width: 4, points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] };
    const store = new MemoryStore();
    const engine = new SyncEngine(store, { fetchFn, online: () => true }, store);
    await store.putDrawing({ id: "d1", strokes: [stroke], updatedAt: 100, deleted: false });
    await engine.flush();
    expect(requests).toContain("POST /api/drawings/sync");

    const store2 = new MemoryStore();
    const engine2 = new SyncEngine(store2, { fetchFn, online: () => true }, store2);
    await engine2.flush();
    expect((await store2.getDrawing("d1"))?.strokes).toHaveLength(1);
    // Cursor 2, not 1: the pulled row sits above the push mark, so the
    // quiescent loop re-pushes it once with identical content (idempotent
    // echo, same as the notes lane) and the echo mints seq 2.
    expect(await store2.getDrawingsCursor()).toBe(2);
    engine.destroy();
    engine2.destroy();
  });

  test("deleteForever pushes the tombstone then drops locally; offline throws", async () => {
    const server = makeServer();
    const store = new MemoryStore([note({ id: "1", updatedAt: 100 })]);
    const onlineEngine = new SyncEngine(store, { fetchFn: server.fetchFn, online: () => true });
    await onlineEngine.deleteForever("1");
    expect(await store.get("1")).toBeUndefined();
    expect(server.rows().get("1")?.deleted).toBe(true);
    onlineEngine.destroy();

    const store2 = new MemoryStore([note({ id: "2", updatedAt: 100 })]);
    const offlineEngine = new SyncEngine(store2, {
      fetchFn: server.fetchFn,
      online: () => false,
    });
    await expect(offlineEngine.deleteForever("2")).rejects.toThrow("offline");
    expect((await store2.get("2"))?.id).toBe("2");
    offlineEngine.destroy();
  });
});
