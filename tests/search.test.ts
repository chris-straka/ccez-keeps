// Ranked-search vectors both clients can mirror: title-prefix >
// title-substring > body match, recency tiebreak, still substring-based.
import { describe, expect, test } from "bun:test";
import { rankNotes } from "../web/store/search.js";
import { newNote, type Note } from "../shared/note.js";

const note = (overrides: Partial<Note> & { id: string }) =>
  newNote({ title: "", body: "", updatedAt: 1000, ...overrides });

describe("rankNotes", () => {
  test("title-prefix beats title-substring beats body match", () => {
    const notes = [
      note({ id: "body", title: "Groceries", body: "buy milk today", updatedAt: 3000 }),
      note({ id: "substr", title: "Buy milk", body: "", updatedAt: 3000 }),
      note({ id: "prefix", title: "Milk run", body: "", updatedAt: 1000 }),
    ];
    const ids = rankNotes(notes, "milk").map((n) => n.id);
    expect(ids).toEqual(["prefix", "substr", "body"]);
  });

  test("recency breaks ties within the same rank", () => {
    const notes = [
      note({ id: "old", title: "Milk A", updatedAt: 1000 }),
      note({ id: "new", title: "Milk B", updatedAt: 2000 }),
    ];
    expect(rankNotes(notes, "milk").map((n) => n.id)).toEqual(["new", "old"]);
  });

  test("matching stays substring-based and case-insensitive", () => {
    const notes = [
      note({ id: "1", title: "OATMILK latte", updatedAt: 1000 }),
      note({ id: "2", title: "Tea", body: "WITH milk?", updatedAt: 2000 }),
      note({ id: "3", title: "Tea", body: "no match here", updatedAt: 3000 }),
    ];
    expect(rankNotes(notes, "  Milk ").map((n) => n.id)).toEqual(["1", "2"]);
  });

  test("empty query returns every note in input order", () => {
    const notes = [note({ id: "b" }), note({ id: "a" })];
    expect(rankNotes(notes, "   ").map((n) => n.id)).toEqual(["b", "a"]);
  });

  test("no match returns an empty list without mutating the input", () => {
    const notes = [note({ id: "1", title: "Hello" })];
    const out = rankNotes(notes, "zzz");
    expect(out).toEqual([]);
    expect(notes).toHaveLength(1);
  });

  test("prefix rank is case-insensitive on the title", () => {
    const notes = [
      note({ id: "sub", title: "a MILK tale", updatedAt: 1000 }),
      note({ id: "pre", title: "milk first", updatedAt: 1000 }),
    ];
    expect(rankNotes(notes, "MILK").map((n) => n.id)).toEqual(["pre", "sub"]);
  });

  test("checklist item text and attachment names match below body", () => {
    const notes = [
      note({
        id: "extra",
        title: "Trip",
        body: "plain",
        checklist: [{ id: "c1", text: "buy oatmilk", checked: false }],
        updatedAt: 1000,
      }),
      note({
        id: "file",
        title: "Trip",
        body: "plain",
        checklist: null,
        attachments: [
          {
            id: "a1",
            name: "oatmilk-label.png",
            mime: "image/png",
            size: 4,
            dataUrl: "data:image/png;base64,iVBORw==",
            thumbUrl: "data:image/png;base64,iVBORw==",
          },
        ],
        updatedAt: 2000,
      }),
      note({ id: "body", title: "Trip", body: "oatmilk latte", updatedAt: 500 }),
      note({ id: "none", title: "Trip", body: "plain", updatedAt: 3000 }),
    ];
    expect(rankNotes(notes, "oatmilk").map((n) => n.id)).toEqual([
      "body",
      "file",
      "extra",
    ]);
  });

  test("extras match case-insensitively", () => {
    const notes = [
      note({
        id: "1",
        title: "t",
        body: "b",
        checklist: [{ id: "c1", text: "BUY Eggs", checked: true }],
        updatedAt: 1000,
      }),
    ];
    expect(rankNotes(notes, "eggs").map((n) => n.id)).toEqual(["1"]);
  });
});
