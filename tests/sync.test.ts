import { describe, expect, test } from "bun:test";
import { isNote, newNote } from "../shared/note.js";
import { changedSince, mergeNoteLists, pickWinner } from "../shared/sync.js";

const base = (overrides = {}) =>
  newNote({ id: "a", title: "t", body: "b", updatedAt: 1000, ...overrides });

describe("isNote", () => {
  test("accepts a valid note, rejects junk", () => {
    expect(isNote(base())).toBe(true);
    expect(isNote(null)).toBe(false);
    expect(isNote({ ...base(), updatedAt: Number.NaN })).toBe(false);
    expect(isNote({ ...base(), id: "" })).toBe(false);
  });

  test("repeat accepts null/daily/weekly, tolerates pre-repeat rows", () => {
    expect(isNote(base({ repeat: null }))).toBe(true);
    expect(isNote(base({ repeat: "daily" }))).toBe(true);
    expect(isNote(base({ repeat: "weekly" }))).toBe(true);
    expect(isNote(base({ repeat: "monthly" }))).toBe(false);
    const { repeat: _dropped, ...legacy } = base();
    expect(isNote(legacy)).toBe(true);
  });
});

describe("pickWinner (last-write-wins)", () => {
  test("newer updatedAt wins either side", () => {
    const oldNote = base({ body: "old", updatedAt: 100 });
    const newNoteValue = base({ body: "new", updatedAt: 200 });
    expect(pickWinner(oldNote, newNoteValue).body).toBe("new");
    expect(pickWinner(newNoteValue, oldNote).body).toBe("new");
  });

  test("equal timestamps break ties deterministically, either order", () => {
    const x = base({ body: "x", updatedAt: 100 });
    const y = base({ body: "y", updatedAt: 100 });
    expect(pickWinner(x, y).body).toBe("y");
    expect(pickWinner(y, x).body).toBe("y");
  });

  test("newer tombstone beats older live note and vice versa", () => {
    const live = base({ deleted: false, updatedAt: 100 });
    const tombstone = base({ deleted: true, updatedAt: 200 });
    expect(pickWinner(live, tombstone).deleted).toBe(true);
    expect(pickWinner(tombstone, live).deleted).toBe(true);
    const oldTombstone = base({ deleted: true, updatedAt: 50 });
    expect(pickWinner(oldTombstone, live).deleted).toBe(false);
  });

  test("rejects id mismatch instead of merging strangers", () => {
    expect(() =>
      pickWinner(newNote({ id: "a", updatedAt: 1 }), newNote({ id: "b", updatedAt: 2 })),
    ).toThrow("id mismatch");
  });
});

describe("mergeNoteLists", () => {
  test("converges with no duplicates and no lost newer edit", () => {
    const deviceA = [
      newNote({ id: "1", body: "A-new", updatedAt: 300 }),
      newNote({ id: "2", body: "A", updatedAt: 100 }),
    ];
    const deviceB = [
      newNote({ id: "1", body: "B-old", updatedAt: 200 }),
      newNote({ id: "3", body: "B", updatedAt: 100 }),
    ];
    const merged = mergeNoteLists(deviceA, deviceB);
    expect(merged).toHaveLength(3);
    expect(new Map(merged.map((n) => [n.id, n.body])).get("1")).toBe("A-new");
    // Symmetric: merge order must not change the converged content.
    const bodies = (list: { id: string; body: string }[]) =>
      [...list].sort((x, y) => (x.id < y.id ? -1 : 1));
    expect(bodies(mergeNoteLists(deviceB, deviceA))).toEqual(bodies(merged));
  });

  test("intra-list duplicates collapse by LWW regardless of order", () => {
    const older = base({ body: "older", updatedAt: 100 });
    const newer = base({ body: "newer", updatedAt: 200 });
    expect(mergeNoteLists([older, newer], [])[0]?.body).toBe("newer");
    expect(mergeNoteLists([newer, older], [])[0]?.body).toBe("newer");
  });
});

describe("changedSince", () => {
  test("strictly-greater cursor (replays the boundary row never twice)", () => {
    const notes = [base({ updatedAt: 100 }), base({ updatedAt: 101 })];
    expect(changedSince(notes, 100)).toHaveLength(1);
    expect(changedSince(notes, 101)).toHaveLength(0);
  });
});
