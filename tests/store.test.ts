// Lane B regression suite: the Store contract, run against BOTH
// implementations so MemoryStore (tests/fixtures) and IdbStore (prod)
// cannot drift. IdbStore runs on fake-indexeddb; no browser needed.
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import "fake-indexeddb/auto";
import { newNote, type Note } from "../shared/note.js";
import type { Drawing } from "../shared/drawing.js";
import { IdbStore } from "../web/store/idb-store.js";
import { MemoryStore } from "../web/store/memory-store.js";
import type { Store } from "../web/store/types.js";
import type { DrawingStore } from "../web/store/drawings.js";

const note = (overrides: Partial<Note> & { id: string }) =>
  newNote({ title: "t", body: "b", updatedAt: 1000, ...overrides });

function suite(name: string, make: () => Store) {
  describe(name, () => {
    let store: Store;
    beforeEach(async () => {
      // Fresh database per test (matters for the IndexedDB backend).
      await new Promise<void>((resolve, reject) => {
        const req = indexedDB.deleteDatabase("ccez-keeps");
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
      store = make();
      await store.ready;
    });
    afterEach(async () => {
      const closable = store as unknown as { close?: () => Promise<void> };
      await closable.close?.();
    });

    test("put/get roundtrip", async () => {
      await store.put(note({ id: "1", body: "hello" }));
      expect((await store.get("1"))?.body).toBe("hello");
      expect(await store.get("missing")).toBeUndefined();
    });

    test("views split notes/archive/trash; pinned first", async () => {
      await store.put(note({ id: "1", updatedAt: 100 }));
      await store.put(note({ id: "2", archived: true, updatedAt: 200 }));
      await store.put(note({ id: "3", pinned: true, updatedAt: 50 }));
      await store.put(note({ id: "4", deleted: true, updatedAt: 300 }));
      expect((await store.list("notes")).map((n) => n.id)).toEqual(["3", "1"]);
      expect((await store.list("archive")).map((n) => n.id)).toEqual(["2"]);
      expect((await store.list("trash")).map((n) => n.id)).toEqual(["4"]);
    });

    test("remove writes tombstone; restore revives", async () => {
      await store.put(note({ id: "1", updatedAt: 100 }));
      await store.remove("1");
      const tomb = await store.get("1");
      expect(tomb?.deleted).toBe(true);
      expect(tomb!.updatedAt).toBeGreaterThan(100);
      expect(await store.list("trash")).toHaveLength(1);
      await store.restore("1");
      const live = await store.get("1");
      expect(live?.deleted).toBe(false);
      expect(live?.archived).toBe(false);
      expect(await store.list("notes")).toHaveLength(1);
    });

    test("search matches title/body case-insensitively", async () => {
      await store.put(note({ id: "1", title: "Grocery MILK run" }));
      await store.put(note({ id: "2", body: "call mom" }));
      await store.put(note({ id: "3", title: "other", body: "unrelated" }));
      expect((await store.search("milk", "notes")).map((n) => n.id)).toEqual(["1"]);
      expect((await store.search("CALL", "notes")).map((n) => n.id)).toEqual(["2"]);
      expect((await store.search("", "notes"))).toHaveLength(3);
    });

    test("export/import roundtrips; bad input rejected", async () => {
      await store.put(note({ id: "1" }));
      const json = await store.exportJson();
      const other = make();
      await other.ready;
      const { imported } = await other.importJson(json);
      expect(imported).toBe(1);
      expect((await other.get("1"))?.id).toBe("1");
      await expect(other.importJson("nope{{{")).rejects.toThrow();
      await expect(other.importJson('{"notes":[{"id":1}]}')).rejects.toThrow();
      const closable = other as unknown as { close?: () => Promise<void> };
      await closable.close?.();
    });

    test("subscribe fires on mutation, stops after unsubscribe", async () => {
      let calls = 0;
      const off = store.subscribe(() => {
        calls += 1;
      });
      await store.put(note({ id: "1" }));
      expect(calls).toBe(1);
      off();
      await store.put(note({ id: "2" }));
      expect(calls).toBe(1);
    });
  });
}

suite("MemoryStore", () => new MemoryStore());
suite("IdbStore", () => new IdbStore());

function drawingSuite(name: string, make: () => Store & DrawingStore) {
  describe(`${name} drawings`, () => {
    let store: Store & DrawingStore;
    beforeEach(async () => {
      await new Promise<void>((resolve, reject) => {
        const req = indexedDB.deleteDatabase("ccez-keeps");
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
      store = make();
      await store.ready;
    });
    afterEach(async () => {
      const closable = store as unknown as { close?: () => Promise<void> };
      await closable.close?.();
    });

    const drawing = (id: string): Drawing => ({
      id,
      strokes: [{ color: "white", width: 4, points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] }],
      updatedAt: 100,
      deleted: false,
    });

    test("put/get roundtrip; missing is undefined", async () => {
      await store.putDrawing(drawing("d1"));
      expect((await store.getDrawing("d1"))?.strokes).toHaveLength(1);
      expect(await store.getDrawing("missing")).toBeUndefined();
    });

    test("rejects invalid drawings", async () => {
      await expect(store.putDrawing({ id: "x" } as unknown as Drawing)).rejects.toThrow();
    });

    test("drawings marks default to zero", async () => {
      expect(await store.getDrawingsCursor()).toBe(0);
      expect(await store.getDrawingsPushMark()).toBe(0);
      await store.setDrawingsCursor(7);
      await store.setDrawingsPushMark(9);
      expect(await store.getDrawingsCursor()).toBe(7);
      expect(await store.getDrawingsPushMark()).toBe(9);
    });
  });
}

drawingSuite("MemoryStore", () => new MemoryStore());
drawingSuite("IdbStore", () => new IdbStore());

describe("IdbStore persistence", () => {
  test("cursor and push mark survive reopen", async () => {
    const first = new IdbStore();
    await first.ready;
    await first.setCursor(123);
    await first.setPushMark(456);
    const second = new IdbStore();
    await second.ready;
    expect(await second.getCursor()).toBe(123);
    expect(await second.getPushMark()).toBe(456);
    await first.close();
    await second.close();
  });
});
