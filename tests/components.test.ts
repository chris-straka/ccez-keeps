// Lane A regression suite: the app shell renders from the store, filters,
// and drives mutations — with a fetch that throws, proving the UI layer
// never touches the network (sync is lane B's job).
import { describe, expect, test } from "bun:test";
import { Window } from "happy-dom";

const window = new Window({ url: "http://localhost/" });
const globals = globalThis as unknown as Record<string, unknown>;
globals["window"] = window;
globals["document"] = window.document;
globals["HTMLElement"] = window.HTMLElement;
globals["customElements"] = window.customElements;
globals["Event"] = window.Event;
globals["CustomEvent"] = window.CustomEvent;
globals["Node"] = window.Node;
globals["localStorage"] = window.localStorage;

import type { KeepsApp } from "../web/components/keeps-app.js";
await import("../web/components/keeps-app.js");
await import("../web/components/note-card.js");
await import("../web/components/note-editor.js");
await import("../web/components/drawing-dialog.js");
const { MemoryStore } = await import("../web/store/memory-store.js");
const { makeFixture } = await import("../web/fixture.js");
const { newNote } = await import("../shared/note.js");
const { DevicesClient } = await import("../web/components/devices.js");
import type { Note } from "../shared/note.js";

const note = (overrides: Partial<Note> & { id: string }) =>
  newNote({ title: "t", body: "b", updatedAt: 1000, ...overrides });

function mount(initial: Note[] = [], devices?: InstanceType<typeof DevicesClient>) {
  let fetchCalls = 0;
  (globalThis as unknown as Record<string, unknown>)["fetch"] = () => {
    fetchCalls += 1;
    throw new Error("components must not fetch");
  };
  const deletedForever: string[] = [];
  let pushes = 0;
  const store = new MemoryStore(initial);
  // Shared happy-dom document across tests: reset the URL so hash routing
  // from a previous test never leaks into the next mount.
  window.location.hash = "";
  try {
    window.localStorage.removeItem("keeps-list");
  } catch {
    // Storage is a nicety in tests too.
  }
  const app = document.createElement("keeps-app") as KeepsApp;
  document.body.appendChild(app);
  const ready = app.connect(
    store,
    {
      schedulePush: () => {
        pushes += 1;
      },
      deleteForever: async (id: string) => {
        deletedForever.push(id);
      },
    },
    devices,
  );
  const tick = () => new Promise((r) => setTimeout(r, 0));
  const cards = () => [...app.querySelectorAll("note-card")];
  const cleanup = () => {
    app.remove();
    document.body.innerHTML = "";
  };
  return {
    store,
    app,
    ready,
    tick,
    cards,
    cleanup,
    stats: () => ({ fetchCalls, pushes, deletedForever }),
  };
}

describe("keeps-app", () => {
  test("main.ts keeps the side-effect import (bundlers drop value-unused named imports, which silently unregisters <keeps-app>)", async () => {
    const source = await Bun.file("web/main.ts").text();
    expect(source).toContain('import "./components/keeps-app.js"');
  });

  test("renders notes from the store with zero network calls", async () => {
    const t = mount([
      note({ id: "1", title: "Milk" }),
      note({ id: "2", title: "Archived", archived: true }),
    ]);
    await t.ready;
    expect(t.cards()).toHaveLength(1);
    expect(t.cards()[0]?.textContent).toContain("Milk");
    expect(t.stats().fetchCalls).toBe(0);
    t.cleanup();
  });

  test("search filters the grid", async () => {
    const t = mount([note({ id: "1", title: "Buy milk" }), note({ id: "2", title: "Call mom" })]);
    await t.ready;
    const input = t.app.querySelector<HTMLInputElement>(".search")!;
    input.value = "milk";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    expect(t.cards()[0]?.textContent).toContain("Buy milk");
    t.cleanup();
  });

  test("sidebar switches to archive view", async () => {
    const t = mount([note({ id: "1", title: "Live" }), note({ id: "2", title: "Old", archived: true })]);
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-view="archive"]')!.click();
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    expect(t.cards()[0]?.textContent).toContain("Old");
    t.cleanup();
  });

  test("hash routes to views and nav writes the hash", async () => {
    const t = mount([note({ id: "1", title: "Soon", reminderAt: 1000 })]);
    await t.ready;
    await t.tick();
    window.location.hash = "#/reminders";
    window.dispatchEvent(new window.Event("hashchange"));
    await t.tick();
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    expect(
      t.app.querySelector('[data-view="reminders"]')?.getAttribute("aria-current"),
    ).toBe("page");
    t.app.querySelector<HTMLButtonElement>('[data-view="notes"]')!.click();
    await t.tick();
    expect(window.location.hash).toBe("#/notes");
    t.cleanup();
  });

  test("layout toggle switches grid/list and persists", async () => {
    const t = mount([note({ id: "1", title: "A" })]);
    await t.ready;
    expect(t.app.querySelector(".grid")?.classList.contains("is-list")).toBe(false);
    t.app.querySelector<HTMLButtonElement>("[data-layout-toggle]")!.click();
    await t.tick();
    expect(t.app.querySelector(".grid")?.classList.contains("is-list")).toBe(true);
    expect(window.localStorage.getItem("keeps-list")).toBe("1");
    t.cleanup();
  });

  test("sidebar theme toggle shows the current theme icon", async () => {
    const t = mount();
    await t.ready;
    const btn = t.app.querySelector(".theme-toggle")!;
    // Icon and label always agree, and a click flips the pair.
    const before = btn.getAttribute("aria-label")!;
    expect(["Switch to light theme", "Switch to dark theme"]).toContain(before);
    expect(btn.textContent).toContain(
      before === "Switch to light theme" ? "Dark mode" : "Light mode",
    );
    (btn as HTMLButtonElement).click();
    const after = btn.getAttribute("aria-label");
    expect(after).not.toBe(before);
    expect(btn.textContent).toContain(
      after === "Switch to light theme" ? "Dark mode" : "Light mode",
    );
    t.cleanup();
  });

  test("dragging a card onto archive files it there", async () => {
    const t = mount([note({ id: "1", title: "File me" })]);
    await t.ready;
    await t.tick();
    const card = t.cards()[0]!;
    expect(card.getAttribute("draggable")).toBe("true");
    const drop = new Event("drop", { bubbles: true }) as Event & {
      dataTransfer: { getData: () => string };
    };
    drop.dataTransfer = { getData: () => "1" };
    t.app.querySelector('[data-drop="archive"]')!.dispatchEvent(drop);
    await t.tick();
    await t.tick();
    expect((await t.store.get("1"))?.archived).toBe(true);
    expect(t.stats().pushes).toBe(1);
    t.cleanup();
  });

  test("reminders view offers a reminder-first composer", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-view="reminders"]')!.click();
    await t.tick();
    expect(t.app.querySelector("[data-new]")?.textContent).toBe("+ New reminder");
    t.cleanup();
  });

  test("editor Ctrl+Z walks history without toolbar buttons", async () => {
    const t = mount([note({ id: "1", title: "T", body: "one" })]);
    await t.ready;
    t.cards()[0]?.dispatchEvent(new Event("click", { bubbles: true }));
    await t.tick();
    const body = t.app.querySelector<HTMLTextAreaElement>(".editor-body")!;
    body.value = "one two";
    body.dispatchEvent(new Event("input", { bubbles: true }));
    const editor = t.app.querySelector("note-editor")!;
    const key = new window.KeyboardEvent("keydown", {
      key: "z",
      ctrlKey: true,
      bubbles: true,
    });
    editor.dispatchEvent(key as unknown as Event);
    expect(
      t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value,
    ).toBe("one");
    t.cleanup();
  });

  test("sidebar reminders view shows firing notes in time order", async () => {
    const t = mount([
      note({ id: "plain", title: "Plain" }),
      note({ id: "later", title: "Later", reminderAt: 9000 }),
      note({ id: "soon", title: "Soon", reminderAt: 1000 }),
    ]);
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-view="reminders"]')!.click();
    await t.tick();
    expect(t.cards()).toHaveLength(2);
    expect(t.cards()[0]?.textContent).toContain("Soon");
    expect(t.cards()[1]?.textContent).toContain("Later");
    t.cleanup();
  });

  test("composer creates a note and schedules a push", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-composer="open"]')!.click();
    await t.tick();
    t.app.querySelector<HTMLInputElement>(".composer-title")!.value = "Shopping";
    t.app.querySelector<HTMLTextAreaElement>(".composer-body")!.value = "eggs";
    t.app.querySelector<HTMLButtonElement>('[data-composer="save"]')!.click();
    await t.tick();
    const all = await t.store.all();
    expect(all).toHaveLength(1);
    expect(all[0]?.title).toBe("Shopping");
    expect(t.stats().pushes).toBe(1);
    t.cleanup();
  });

  test("pin toggle persists and schedules a push", async () => {
    const t = mount([note({ id: "1", title: "Pinnable" })]);
    await t.ready;
    t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="pin"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.pinned).toBe(true);
    expect(t.stats().pushes).toBe(1);
    t.cleanup();
  });

  test("clicking a card opens the editor; save updates the note", async () => {
    const t = mount([note({ id: "1", title: "Before", body: "x" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    const editor = t.app.querySelector(".editor");
    expect(editor).not.toBeNull();
    t.app.querySelector<HTMLInputElement>(".editor-title")!.value = "After";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.title).toBe("After");
    t.cleanup();
  });

  test("delete moves to trash; restore revives; delete-forever calls sync", async () => {
    const t = mount([note({ id: "1", title: "Doomed" })]);
    await t.ready;
    t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="delete"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.deleted).toBe(true);
    t.app.querySelector<HTMLButtonElement>('[data-view="trash"]')!.click();
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="delete-forever"]')!.click();
    await t.tick();
    expect(t.stats().deletedForever).toEqual(["1"]);
    t.cleanup();
  });

  test("note content is escaped, never markup", async () => {
    const t = mount([note({ id: "1", title: "<img src=x onerror=y>", body: "<b>hi</b>" })]);
    await t.ready;
    expect(t.cards()[0]?.querySelector("img")).toBeNull();
    expect(t.cards()[0]?.querySelector("b")).toBeNull();
    expect(t.cards()[0]?.textContent).toContain("<img src=x onerror=y>");
    t.cleanup();
  });

  test("sidebar starts closed; hamburger toggles the drawer", async () => {
    const t = mount([note({ id: "1", title: "Live" })]);
    await t.ready;
    expect(t.app.querySelector(".layout")!.classList.contains("nav-open")).toBe(false);
    const toggle = t.app.querySelector<HTMLButtonElement>('[data-nav="toggle"]')!;
    toggle.click();
    expect(t.app.querySelector(".layout")!.classList.contains("nav-open")).toBe(true);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    // Picking a view works from the opened drawer and keeps it open
    // on desktop (it only auto-closes on narrow viewports).
    t.app.querySelector<HTMLButtonElement>('[data-view="notes"]')!.click();
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    expect(t.app.querySelector(".layout")!.classList.contains("nav-open")).toBe(true);
    t.cleanup();
  });

  test("New button opens a blank editor; save creates a note and pushes", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-new="open"]')!.click();
    await t.tick();
    expect(t.app.querySelector(".editor")).not.toBeNull();
    t.app.querySelector<HTMLInputElement>(".editor-title")!.value = "Fresh";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    const all = await t.store.all();
    expect(all).toHaveLength(1);
    expect(all[0]?.title).toBe("Fresh");
    expect(t.stats().pushes).toBe(1);
    t.cleanup();
  });

  test("editor undo/redo walks the text history", async () => {
    const t = mount([note({ id: "1", title: "Before", body: "x" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    const body = t.app.querySelector<HTMLTextAreaElement>(".editor-body")!;
    const undo = () => t.app.querySelector<HTMLButtonElement>('[data-action="undo"]')!.click();
    const redo = () => t.app.querySelector<HTMLButtonElement>('[data-action="redo"]')!.click();
    // Fresh dialog: nothing to undo yet.
    expect(t.app.querySelector<HTMLButtonElement>('[data-action="undo"]')!.disabled).toBe(true);
    body.value = "first";
    body.dispatchEvent(new Event("input", { bubbles: true }));
    body.value = "second";
    body.dispatchEvent(new Event("input", { bubbles: true }));
    undo();
    expect(body.value).toBe("first");
    undo();
    expect(body.value).toBe("x");
    redo();
    expect(body.value).toBe("first");
    // Save persists the redone text.
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.body).toBe("first");
    t.cleanup();
  });

  test("editor list button toggles plain-text checklist markers", async () => {
    const t = mount([note({ id: "1", title: "Shop", body: "milk" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    const list = () => t.app.querySelector<HTMLButtonElement>('[data-action="list"]')!.click();
    list();
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value).toBe("- [ ] milk");
    list();
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value).toBe("- [x] milk");
    list();
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value).toBe("- [ ] milk");
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    // Stored as plain text: the frozen Note contract is untouched.
    expect((await t.store.get("1"))?.body).toBe("- [ ] milk");
    t.cleanup();
  });

  test("structured checklist edits, adds, and saves with a body fallback", async () => {
    const t = mount([
      note({
        id: "1",
        title: "Shop",
        body: "milk\neggs",
        checklist: [
          { id: "c1", text: "milk", checked: false },
          { id: "c2", text: "eggs", checked: true },
        ],
      }),
    ]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-checklist")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    // Item rows render instead of the textarea.
    expect(t.app.querySelector(".editor-body")).toBeNull();
    const rows = [...t.app.querySelectorAll<HTMLInputElement>(".check-text")];
    expect(rows.map((r) => r.value)).toEqual(["milk", "eggs"]);
    // Toggle the first item, rename the second, add a third.
    const first = t.app.querySelectorAll<HTMLInputElement>(".check-toggle")[0]!;
    first.checked = true;
    first.dispatchEvent(new Event("change", { bubbles: true }));
    rows[1]!.value = "free-range eggs";
    rows[1]!.dispatchEvent(new Event("input", { bubbles: true }));
    t.app.querySelector<HTMLButtonElement>('[data-action="check-add"]')!.click();
    const fresh = [...t.app.querySelectorAll<HTMLInputElement>(".check-text")];
    fresh[2]!.value = "bread";
    fresh[2]!.dispatchEvent(new Event("input", { bubbles: true }));
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    const saved = await t.store.get("1");
    expect(saved?.checklist).toEqual([
      { id: "c1", text: "milk", checked: true },
      { id: "c2", text: "free-range eggs", checked: true },
      { id: expect.any(String), text: "bread", checked: false },
    ]);
    // Body fallback keeps older clients readable.
    expect(saved?.body).toBe("milk\nfree-range eggs\nbread");
    t.cleanup();
  });

  test("checklist edits participate in undo/redo", async () => {
    const t = mount([
      note({
        id: "1",
        title: "Shop",
        checklist: [
          { id: "c1", text: "milk", checked: false },
          { id: "c2", text: "eggs", checked: false },
        ],
      }),
    ]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-checklist")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    const undo = () => t.app.querySelector<HTMLButtonElement>('[data-action="undo"]')!.click();
    const redo = () => t.app.querySelector<HTMLButtonElement>('[data-action="redo"]')!.click();
    const texts = () =>
      [...t.app.querySelectorAll<HTMLInputElement>(".check-text")].map((r) => r.value);
    // Toggle, rename, add — then walk it all back.
    const first = t.app.querySelectorAll<HTMLInputElement>(".check-toggle")[0]!;
    first.checked = true;
    first.dispatchEvent(new Event("change", { bubbles: true }));
    const rows = [...t.app.querySelectorAll<HTMLInputElement>(".check-text")];
    rows[1]!.value = "free-range eggs";
    rows[1]!.dispatchEvent(new Event("input", { bubbles: true }));
    t.app.querySelector<HTMLButtonElement>('[data-action="check-add"]')!.click();
    expect(texts()).toEqual(["milk", "free-range eggs", ""]);
    undo();
    expect(texts()).toEqual(["milk", "free-range eggs"]);
    undo();
    expect(texts()).toEqual(["milk", "eggs"]);
    undo();
    expect(t.app.querySelectorAll<HTMLInputElement>(".check-toggle")[0]!.checked).toBe(
      false,
    );
    redo();
    expect(t.app.querySelectorAll<HTMLInputElement>(".check-toggle")[0]!.checked).toBe(
      true,
    );
    t.cleanup();
  });

  test("checklist mode converts body lines to items and back", async () => {
    const t = mount([note({ id: "1", title: "Shop", body: "milk\n\neggs" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    t.app.querySelector<HTMLButtonElement>('[data-action="checklist-mode"]')!.click();
    expect(
      [...t.app.querySelectorAll<HTMLInputElement>(".check-text")].map((r) => r.value),
    ).toEqual(["milk", "eggs"]);
    t.app.querySelector<HTMLButtonElement>('[data-action="checklist-mode"]')!.click();
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value).toBe(
      "milk\neggs",
    );
    t.cleanup();
  });

  test("card previews checklist rows and attachment thumbs", async () => {
    const items = Array.from({ length: 7 }, (_, i) => ({
      id: `c${i}`,
      text: `item ${i}`,
      checked: i === 0,
    }));
    const t = mount([
      note({
        id: "1",
        title: "Trip",
        body: "fallback",
        checklist: items,
        attachments: [
          {
            id: "a1",
            name: "beach.png",
            mime: "image/png",
            size: 4,
            dataUrl: "data:image/png;base64,iVBORw==",
            thumbUrl: "data:image/png;base64,iVBORw==",
          },
        ],
      }),
    ]);
    await t.ready;
    const card = t.cards()[0]!;
    // Structured preview replaces the body fallback.
    expect(card.querySelector(".card-body")).toBeNull();
    expect(card.querySelectorAll(".card-checklist .check-row")).toHaveLength(5);
    expect(card.querySelector(".check-more")?.textContent).toBe("+2 more");
    expect(
      card.querySelector(".check-row.is-checked .check-preview-text")?.textContent,
    ).toBe("item 0");
    const thumb = card.querySelector(".card-thumb") as HTMLImageElement;
    expect(thumb?.src).toBe("data:image/png;base64,iVBORw==");
    expect(thumb?.alt).toBe("beach.png");
    t.cleanup();
  });

  test("editor stages existing attachments and removes one on save", async () => {
    const a1 = {
      id: "a1",
      name: "one.png",
      mime: "image/png",
      size: 4,
      dataUrl: "data:image/png;base64,iVBORw==",
      thumbUrl: "data:image/png;base64,iVBORw==",
    };
    const a2 = { ...a1, id: "a2", name: "two.png" };
    const t = mount([note({ id: "1", title: "Pics", attachments: [a1, a2] })]);
    await t.ready;
    t.cards()[0]?.dispatchEvent(new Event("click", { bubbles: true }));
    await t.tick();
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(2);
    t.app.querySelector<HTMLElement>('[data-attach-del="a1"]')!.click();
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(1);
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.attachments).toEqual([a2]);
    t.cleanup();
  });

  test("card renders markdown subset as markup, still escaped", async () => {
    const t = mount([note({ id: "1", title: "t", body: "a **b** and <i>x</i>" })]);
    await t.ready;
    const body = t.cards()[0]?.querySelector(".card-body")!;
    expect(body.querySelector("strong")?.textContent).toBe("b");
    expect(body.querySelector("i")).toBeNull();
    expect(body.textContent).toContain("<i>x</i>");
    t.cleanup();
  });

  test("background store update keeps the open editor draft", async () => {
    const t = mount([note({ id: "1", title: "Mine", body: "typing…" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value = "typing… more";
    // A sync landing mid-edit re-renders the shell around the dialog.
    await t.store.put(note({ id: "2", title: "Fresh" }));
    await t.tick();
    expect(t.app.querySelector(".editor")).not.toBeNull();
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value).toBe(
      "typing… more",
    );
    t.cleanup();
  });

  test("composer draft survives a store refresh", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-composer="open"]')!.click();
    await t.tick();
    t.app.querySelector<HTMLInputElement>(".composer-title")!.value = "Half";
    t.app.querySelector<HTMLTextAreaElement>(".composer-body")!.value = "written";
    await t.store.put(note({ id: "9", title: "Other" }));
    await t.tick();
    expect(t.app.querySelector<HTMLInputElement>(".composer-title")!.value).toBe("Half");
    expect(t.app.querySelector<HTMLTextAreaElement>(".composer-body")!.value).toBe("written");
    t.cleanup();
  });

  test("Draw button asks for a drawing; save attaches the marker", async () => {
    const t = mount([note({ id: "1", title: "t", body: "hello" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    let opened = 0;
    t.app.addEventListener("drawing-open", () => {
      opened += 1;
    });
    t.app.querySelector<HTMLButtonElement>('[data-action="draw"]')!.click();
    expect(opened).toBe(1);
    const strokes = [{ color: "white", width: 4, points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] }];
    t.app.querySelector("note-editor")!.dispatchEvent(
      new CustomEvent("drawing-save", { bubbles: true, composed: true, detail: { strokes } }),
    );
    await t.tick();
    const body = t.app.querySelector<HTMLTextAreaElement>(".editor-body")!.value;
    expect(body).toMatch(/!\[drawing\]\(([^)]+)\)/);
    const id = body.match(/!\[drawing\]\(([^)]+)\)/)?.[1] as string;
    expect((await t.store.getDrawing(id))?.strokes).toHaveLength(1);
    t.cleanup();
  });

  test("card renders a canvas for each drawing marker", async () => {
    const t = mount([note({ id: "1", title: "t", body: "hi\n![drawing](d1)" })]);
    await t.ready;
    await t.store.putDrawing({
      id: "d1",
      strokes: [{ color: "white", width: 4, points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] }],
      updatedAt: 100,
      deleted: false,
    });
    await t.tick();
    const canvas = t.cards()[0]?.querySelector<HTMLCanvasElement>("canvas.drawing");
    expect(canvas?.dataset["drawing"]).toBe("d1");
    t.cleanup();
  });

  test("editor bold button wraps the body selection", async () => {
    const t = mount([note({ id: "1", title: "t", body: "hello" })]);
    await t.ready;
    t.cards()[0]?.querySelector(".card-body")!.dispatchEvent(
      new Event("click", { bubbles: true }),
    );
    await t.tick();
    const body = t.app.querySelector<HTMLTextAreaElement>(".editor-body")!;
    body.setSelectionRange(0, 5);
    t.app.querySelector<HTMLButtonElement>('[data-action="bold"]')!.click();
    expect(body.value).toBe("**hello**");
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    expect((await t.store.get("1"))?.body).toBe("**hello**");
    expect(t.cards()[0]?.querySelector(".card-body strong")?.textContent).toBe("hello");
    t.cleanup();
  });

  test("syncing status marks the pulse hook; idle clears it", async () => {
    const t = mount();
    await t.ready;
    t.app.setSyncStatus("syncing");
    const el = t.app.querySelector(".sync-status")!;
    expect(el.textContent).toContain("Syncing");
    expect(el.classList.contains("is-syncing")).toBe(true);
    t.app.setSyncStatus("idle");
    expect(el.classList.contains("is-syncing")).toBe(false);
    t.cleanup();
  });

  describe("theme", () => {
    function clearTheme(): void {
      window.localStorage.clear();
      document.documentElement.removeAttribute("data-theme");
    }

    function systemTheme(): "light" | "dark" {
      const media =
        typeof window.matchMedia === "function"
          ? window.matchMedia("(prefers-color-scheme: light)")
          : null;
      return media?.matches ? "light" : "dark";
    }

    test("defaults to the system theme with no stored choice", async () => {
      clearTheme();
      const expected = systemTheme();
      const t = mount();
      await t.ready;
      expect(document.documentElement.dataset["theme"]).toBe(expected);
      expect(
        t.app.querySelector(".theme-toggle")?.getAttribute("aria-label"),
      ).toBe(
        expected === "dark" ? "Switch to light theme" : "Switch to dark theme",
      );
      t.cleanup();
      clearTheme();
    });

    test("toggle flips the theme and persists the choice", async () => {
      clearTheme();
      const start = systemTheme();
      const flipped = start === "dark" ? "light" : "dark";
      const t = mount();
      await t.ready;
      t.app.querySelector<HTMLButtonElement>(".theme-toggle")!.click();
      expect(document.documentElement.dataset["theme"]).toBe(flipped);
      expect(window.localStorage.getItem("keeps-theme")).toBe(flipped);
      t.app.querySelector<HTMLButtonElement>(".theme-toggle")!.click();
      expect(document.documentElement.dataset["theme"]).toBe(start);
      expect(window.localStorage.getItem("keeps-theme")).toBe(start);
      t.cleanup();
      clearTheme();
    });

    test("a stored choice wins on boot", async () => {
      clearTheme();
      window.localStorage.setItem("keeps-theme", "light");
      const t = mount();
      await t.ready;
      expect(document.documentElement.dataset["theme"]).toBe("light");
      t.cleanup();
      clearTheme();
    });
  });

  test("empty trash arms on first click, deletes each item on second", async () => {
    const t = mount([
      note({ id: "1", title: "Gone", deleted: true }),
      note({ id: "2", title: "Live" }),
    ]);
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-view="trash"]')!.click();
    await t.tick();
    expect(t.cards()).toHaveLength(1);
    const button = () => t.app.querySelector<HTMLButtonElement>("[data-empty-trash]")!;
    expect(t.app.querySelector(".trash-head")?.textContent).toContain("1 item");
    expect(button().textContent).toBe("Empty trash");
    button().click();
    await t.tick();
    await t.tick();
    expect(button().textContent).toBe("Click again to confirm");
    expect(t.stats().deletedForever).toEqual([]);
    button().click();
    await t.tick();
    await t.tick();
    expect(t.stats().deletedForever).toEqual(["1"]);
    t.cleanup();
  });

  test("500-note fixture renders completely", async () => {
    const fixture = makeFixture(500);
    const expected = fixture.filter((n) => !n.archived && !n.deleted).length;
    const t = mount(fixture);
    const start = Date.now();
    await t.ready;
    const duration = Date.now() - start;
    expect(t.cards()).toHaveLength(expected);
    console.log(`500-note render: ${duration}ms (${expected} visible cards)`);
    expect(duration).toBeLessThan(10_000);
    t.cleanup();
  });

  describe("devices panel", () => {
    function json(status: number, body: unknown): Response {
      return new Response(JSON.stringify(body), { status });
    }

    function deviceStub() {
      let devices = [
        { id: "d1", deviceName: "pixel", createdAt: 1000, lastSeenAt: 2000, revoked: false },
        { id: "d2", deviceName: "old", createdAt: 1000, lastSeenAt: 2000, revoked: true },
      ];
      const calls: { url: string; body: unknown }[] = [];
      const fetchFn = (async (url: unknown, init?: unknown) => {
        const path = String(url);
        if (!init) return json(200, { devices });
        const body = JSON.parse((init as { body: string }).body) as {
          deviceId: string;
          deviceName?: string;
        };
        calls.push({ url: path, body });
        if (path === "/api/devices/rename") {
          devices = devices.map((d) =>
            d.id === body.deviceId ? { ...d, deviceName: body.deviceName ?? d.deviceName } : d,
          );
        }
        if (path === "/api/devices/revoke") {
          devices = devices.map((d) =>
            d.id === body.deviceId ? { ...d, revoked: true } : d,
          );
        }
        return json(200, {});
      }) as unknown as typeof fetch;
      return { client: new DevicesClient(fetchFn), calls };
    }

    async function openDevices(
      t: ReturnType<typeof mount>,
    ): Promise<void> {
      t.app.querySelector<HTMLButtonElement>('[data-view="devices"]')!.click();
      await t.tick();
      await t.tick();
      await t.tick();
    }

    test("Devices nav renders live and revoked rows", async () => {
      const { client } = deviceStub();
      const t = mount([], client);
      await t.ready;
      await openDevices(t);
      const rows = [...t.app.querySelectorAll(".device-row")];
      expect(rows).toHaveLength(2);
      expect(rows[0]?.querySelector<HTMLInputElement>(".device-name")?.value).toBe("pixel");
      expect(rows[1]?.textContent).toContain("Revoked");
      t.cleanup();
    });

    test("rename posts the edited name and confirms", async () => {
      const { client, calls } = deviceStub();
      const t = mount([], client);
      await t.ready;
      await openDevices(t);
      const row = t.app.querySelector('.device-row[data-id="d1"]')!;
      row.querySelector<HTMLInputElement>(".device-name")!.value = "pixel-9";
      row.querySelector<HTMLButtonElement>('[data-device-action="rename"]')!.click();
      await t.tick();
      await t.tick();
      await t.tick();
      expect(calls).toEqual([
        { url: "/api/devices/rename", body: { deviceId: "d1", deviceName: "pixel-9" } },
      ]);
      expect(t.app.querySelector(".devices-status")?.textContent).toBe("Renamed.");
      t.cleanup();
    });

    test("revoke signs the phone out and shows the badge", async () => {
      const { client, calls } = deviceStub();
      const t = mount([], client);
      await t.ready;
      await openDevices(t);
      t.app
        .querySelector('.device-row[data-id="d1"] [data-device-action="revoke"]')!
        .dispatchEvent(new Event("click", { bubbles: true }));
      await t.tick();
      await t.tick();
      await t.tick();
      expect(calls).toEqual([{ url: "/api/devices/revoke", body: { deviceId: "d1" } }]);
      expect(t.app.querySelector(".devices-status")?.textContent).toContain("signed out");
      expect(t.app.querySelector('.device-row[data-id="d1"]')?.textContent).toContain(
        "Revoked",
      );
      t.cleanup();
    });

    test("a failed list shows the error state, never a blank panel", async () => {
      const failing = new DevicesClient(
        (async () => json(500, {})) as unknown as typeof fetch,
      );
      const t = mount([], failing);
      await t.ready;
      await openDevices(t);
      expect(t.app.querySelector(".device-list")?.textContent).toContain(
        "Couldn't load devices",
      );
      t.cleanup();
    });
  });

  describe("export/import + delete undo", () => {
    test("export triggers a download with the store JSON", async () => {
      const t = mount([note({ id: "1", title: "Milk" })]);
      await t.ready;
      const g = globalThis as unknown as Record<string, any>;
      const origCreate = g["URL"].createObjectURL;
      const origRevoke = g["URL"].revokeObjectURL;
      let captured: Blob | undefined;
      g["URL"].createObjectURL = (blob: Blob) => {
        captured = blob;
        return "blob:test-export";
      };
      g["URL"].revokeObjectURL = () => {};
      const anchorProto = (window as unknown as Record<string, any>)[
        "HTMLAnchorElement"
      ].prototype as { click: () => void };
      const origClick = anchorProto.click;
      const clicks: { href: string; download: string }[] = [];
      anchorProto.click = function (this: HTMLAnchorElement) {
        clicks.push({ href: this.href, download: this.download });
      };
      try {
        t.app.querySelector<HTMLButtonElement>('[data-view="settings"]')!.click();
        await t.tick();
        t.app.querySelector<HTMLButtonElement>("[data-export]")!.click();
        await t.tick();
        await t.tick();
        expect(captured).toBeDefined();
        expect(await captured!.text()).toContain("Milk");
        expect(clicks).toHaveLength(1);
        expect(clicks[0]!.download).toBe("keeps-export.json");
      } finally {
        g["URL"].createObjectURL = origCreate;
        g["URL"].revokeObjectURL = origRevoke;
        anchorProto.click = origClick;
      }
      t.cleanup();
    });

    test("import round-trips a note and schedules a push", async () => {
      const t = mount();
      await t.ready;
      t.app.querySelector<HTMLButtonElement>('[data-view="settings"]')!.click();
      await t.tick();
      const payload = JSON.stringify({
        version: 1,
        notes: [note({ id: "imp1", title: "Imported" })],
      });
      const file = new File([payload], "keeps.json", {
        type: "application/json",
      });
      const input = t.app.querySelector<HTMLInputElement>(".import-file")!;
      Object.defineProperty(input, "files", {
        value: [file],
        configurable: true,
      });
      input.dispatchEvent(new Event("change", { bubbles: true }));
      await t.tick();
      await t.tick();
      await t.tick();
      expect((await t.store.get("imp1"))?.title).toBe("Imported");
      expect(t.stats().pushes).toBe(1);
      t.cleanup();
    });

    test("delete-then-undo restores the note and pushes", async () => {
      const t = mount([note({ id: "1", title: "Doomed" })]);
      await t.ready;
      t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="delete"]')!.click();
      await t.tick();
      await t.tick();
      expect((await t.store.get("1"))?.deleted).toBe(true);
      expect(t.stats().pushes).toBe(1);
      expect(t.app.querySelector(".toast")?.textContent).toContain("Deleted");
      const undo = t.app.querySelector<HTMLButtonElement>('[data-toast="undo"]')!;
      expect(undo.textContent).toBe("Undo");
      undo.click();
      await t.tick();
      await t.tick();
      const restored = await t.store.get("1");
      expect(restored?.deleted).toBe(false);
      expect(restored?.title).toBe("Doomed");
      expect(t.stats().pushes).toBe(2);
      expect(t.app.querySelector(".toast")).toBeNull();
      t.cleanup();
    });

    test("expired undo leaves the deletion intact", async () => {
      const t = mount([note({ id: "1", title: "Doomed" })]);
      await t.ready;
      (t.app as unknown as { undoMs: number }).undoMs = 30;
      t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="delete"]')!.click();
      await t.tick();
      expect(t.app.querySelector(".toast")).not.toBeNull();
      await new Promise((r) => setTimeout(r, 100));
      await t.tick();
      expect(t.app.querySelector(".toast")).toBeNull();
      expect((await t.store.get("1"))?.deleted).toBe(true);
      t.cleanup();
    });

    test("delete-forever shows no undo toast", async () => {
      const t = mount([note({ id: "1", title: "Doomed" })]);
      await t.ready;
      await t.store.remove("1");
      t.app.querySelector<HTMLButtonElement>('[data-view="trash"]')!.click();
      await t.tick();
      t.cards()[0]?.querySelector<HTMLButtonElement>('[data-action="delete-forever"]')!.click();
      await t.tick();
      expect(t.stats().deletedForever).toEqual(["1"]);
      expect(t.app.querySelector(".toast")).toBeNull();
      t.cleanup();
    });
  });
});

describe("keeps-app labels + reminders (WEB-CLIENTS)", () => {
  async function mountWithLabels(
    initial: Note[] = [],
    labelSeed: { id: string; name: string; color?: string }[] = [],
  ) {
    // Same shared-document hygiene as mount(): hash routing must not leak.
    window.location.hash = "";
    try {
      window.localStorage.removeItem("keeps-list");
    } catch {
      // Storage is a nicety in tests too.
    }
    const { LabelsStore } = await import("../web/store/labels.js");
    (globalThis as unknown as Record<string, unknown>)["fetch"] = () => {
      throw new Error("components must not fetch");
    };
    const store = new MemoryStore(initial);
    const labels = new LabelsStore();
    for (const s of labelSeed) {
      labels.put({
        id: s.id,
        name: s.name,
        color: s.color ?? "default",
        updatedAt: 1000,
        deleted: false,
      });
    }
    const app = document.createElement("keeps-app") as KeepsApp;
    document.body.appendChild(app);
    const ready = app.connect(
      store,
      {
        schedulePush: () => {},
        deleteForever: async () => {},
      },
      undefined,
      labels,
    );
    const tick = () => new Promise((r) => setTimeout(r, 0));
    const cards = () => [...app.querySelectorAll("note-card")] as HTMLElement[];
    const cleanup = () => {
      app.remove();
      document.body.innerHTML = "";
    };
    return { store, labels, app, ready, tick, cards, cleanup };
  }

  test("label chips render on cards with label names", async () => {
    const t = await mountWithLabels(
      [note({ id: "1", title: "Shop", labelIds: ["l1"] })],
      [{ id: "l1", name: "Errands" }],
    );
    await t.ready;
    await t.tick();
    const chip = t.cards()[0]?.querySelector(".label-chip");
    expect(chip?.textContent).toBe("Errands");
    t.cleanup();
  });

  test("label chips carry their label color, defaulting safely", async () => {
    const t = await mountWithLabels(
      [note({ id: "1", title: "Shop", labelIds: ["l1", "ghost"] })],
      [{ id: "l1", name: "Errands", color: "red" }],
    );
    await t.ready;
    await t.tick();
    const chips = [...t.cards()[0]?.querySelectorAll(".label-chip") ?? []];
    expect(chips.map((c) => c.getAttribute("data-color"))).toEqual(["red", "default"]);
    t.cleanup();
  });

  test("labels view lists labels with live counts", async () => {
    const t = await mountWithLabels(
      [
        note({ id: "1", title: "Shop", labelIds: ["l1"] }),
        note({ id: "2", title: "Read", labelIds: ["l1"] }),
      ],
      [{ id: "l1", name: "Errands" }],
    );
    await t.ready;
    await t.tick();
    t.app.querySelector<HTMLButtonElement>('[data-view="labels"]')!.click();
    await t.tick();
    const row = t.app.querySelector(".label-list .label-row");
    expect(row?.textContent).toContain("Errands");
    expect(row?.querySelector(".label-count")?.textContent).toBe("2");
    expect(t.cards()).toHaveLength(0);
    t.cleanup();
  });

  test("settings view hosts export and import", async () => {
    const t = await mountWithLabels([], []);
    await t.ready;
    await t.tick();
    t.app.querySelector<HTMLButtonElement>('[data-view="settings"]')!.click();
    await t.tick();
    expect(t.app.querySelector("[data-export]")).not.toBeNull();
    expect(t.app.querySelector(".import-file")).not.toBeNull();
    expect(t.app.querySelector(".panel")?.textContent).toContain("Shortcuts");
    const repo = t.app.querySelector<HTMLAnchorElement>('.setting-actions a[href*="github.com"]');
    expect(repo?.href).toBe("https://github.com/chris-straka/ccez-keeps");
    expect(repo?.target).toBe("_blank");
    t.cleanup();
  });

  test("labels panel filter narrows the grid, chip clears it", async () => {
    const t = await mountWithLabels(
      [
        note({ id: "1", title: "Shop", labelIds: ["l1"] }),
        note({ id: "2", title: "Read", labelIds: [] }),
      ],
      [{ id: "l1", name: "Errands" }],
    );
    await t.ready;
    await t.tick();
    expect(t.cards()).toHaveLength(2);
    t.app.querySelector<HTMLButtonElement>('[data-view="labels"]')!.click();
    await t.tick();
    t.app.querySelector<HTMLButtonElement>('[data-label-filter="l1"]')!.click();
    await t.tick();
    // Filtering jumps back to the grid with a clearing chip.
    expect(t.cards()).toHaveLength(1);
    expect(t.cards()[0]?.textContent).toContain("Shop");
    const chip = t.app.querySelector(".filter-chip");
    expect(chip?.textContent).toContain("Errands");
    (chip as HTMLButtonElement).click();
    await t.tick();
    expect(t.cards()).toHaveLength(2);
    t.cleanup();
  });

  test("editor checkbox list assigns a label on save", async () => {
    const t = await mountWithLabels(
      [note({ id: "1", title: "Shop", labelIds: [] })],
      [{ id: "l1", name: "Errands" }],
    );
    await t.ready;
    await t.tick();
    t.cards()[0]!.click();
    await t.tick();
    const box = t.app.querySelector<HTMLInputElement>('.label-check[value="l1"]')!;
    expect(box).not.toBeNull();
    box.checked = true;
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    await t.tick();
    expect((await t.store.get("1"))?.labelIds).toEqual(["l1"]);
    expect(t.cards()[0]?.querySelector(".label-chip")?.textContent).toBe("Errands");
    t.cleanup();
  });

  test("labels panel creates + renames + deletes labels", async () => {
    const t = await mountWithLabels([], [{ id: "l1", name: "Errands" }]);
    await t.ready;
    await t.tick();
    t.app.querySelector<HTMLButtonElement>('[data-view="labels"]')!.click();
    await t.tick();
    t.app.querySelector<HTMLInputElement>(".label-create-input")!.value = "Books";
    t.app.querySelector<HTMLButtonElement>("[data-label-create]")!.click();
    await t.tick();
    expect(t.labels.all().map((l) => l.name)).toContain("Books");
    t.app.querySelector<HTMLButtonElement>('[data-label-rename="l1"]')!.click();
    await t.tick();
    t.app.querySelector<HTMLInputElement>(".label-rename-input")!.value = "Chores";
    t.app.querySelector<HTMLButtonElement>('[data-label-rename-save="l1"]')!.click();
    await t.tick();
    expect(t.labels.get("l1")?.name).toBe("Chores");
    t.app.querySelector<HTMLButtonElement>('[data-label-delete="l1"]')!.click();
    await t.tick();
    expect(t.labels.get("l1")).toBeUndefined();
    t.cleanup();
  });

  test("labels lane syncs through fetch (push then pull)", async () => {
    const { LabelsStore } = await import("../web/store/labels.js");
    const seen: string[] = [];
    const fakeFetch = (async (url: unknown, init?: { body?: unknown }) => {
      seen.push(String(url));
      if (String(url).startsWith("/api/labels/sync")) {
        return new Response(JSON.stringify({ deltas: [], cursor: 7 }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          labels: [
            { id: "s1", name: "Server", color: "default", updatedAt: 5000, deleted: false },
          ],
          cursor: 8,
        }),
        { status: 200 },
      );
    }) as typeof fetch;
    const labels = new LabelsStore({ fetchFn: fakeFetch });
    labels.put({ id: "c1", name: "Client", color: "default", updatedAt: 1000, deleted: false });
    await labels.flush();
    expect(seen[0]).toBe("/api/labels/sync");
    expect(seen[1]).toBe("/api/labels?since=7");
    expect(labels.get("s1")?.name).toBe("Server");
    expect(labels.get("c1")?.name).toBe("Client");
  });

  test("editor reminder field sets reminderAt; card shows Reminds date", async () => {
    const t = await mountWithLabels([note({ id: "1", title: "Call" })]);
    await t.ready;
    await t.tick();
    t.cards()[0]!.click();
    await t.tick();
    const input = t.app.querySelector<HTMLInputElement>(".editor-reminder")!;
    expect(input).not.toBeNull();
    expect(input.value).toBe("");
    input.value = "2030-05-01T10:00";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    await t.tick();
    const saved = await t.store.get("1");
    expect(saved?.reminderAt).not.toBeNull();
    expect(typeof saved?.reminderAt).toBe("number");
    const reminder = t.cards()[0]?.querySelector(".reminder");
    expect(reminder?.textContent).toContain("Reminds");
    expect(reminder?.classList.contains("is-overdue")).toBe(false);
    t.cleanup();
  });

  test("editor repeat selector saves daily; card shows the rule", async () => {
    const t = await mountWithLabels([note({ id: "1", title: "Call" })]);
    await t.ready;
    await t.tick();
    t.cards()[0]!.click();
    await t.tick();
    t.app.querySelector<HTMLInputElement>(".editor-reminder")!.value = "2030-05-01T10:00";
    t.app.querySelector<HTMLSelectElement>(".editor-repeat")!.value = "daily";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    await t.tick();
    expect((await t.store.get("1"))?.repeat).toBe("daily");
    expect(t.cards()[0]?.querySelector(".reminder")?.textContent).toContain("Daily");
    t.cleanup();
  });

  test("repeat without a reminder saves null", async () => {
    const t = await mountWithLabels([note({ id: "1", title: "Call" })]);
    await t.ready;
    await t.tick();
    t.cards()[0]!.click();
    await t.tick();
    t.app.querySelector<HTMLSelectElement>(".editor-repeat")!.value = "weekly";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    await t.tick();
    expect((await t.store.get("1"))?.repeat).toBeNull();
    t.cleanup();
  });

  test("due reminder toasts once; View opens the note", async () => {
    const t = mount([note({ id: "1", title: "Call", reminderAt: Date.now() - 1000 })]);
    await t.ready;
    await t.tick();
    await t.tick();
    expect(t.app.querySelector(".toast")?.textContent).toContain("Reminder: Call");
    t.app.querySelector<HTMLButtonElement>('[data-toast="view"]')!.click();
    await t.tick();
    await t.tick();
    expect(t.app.querySelector("note-editor")).not.toBeNull();
    t.cleanup();
  });

  test("past reminder renders overdue style; clearing the field removes it", async () => {
    const t = await mountWithLabels([
      note({ id: "1", title: "Late", reminderAt: 1000 }),
      note({ id: "2", title: "Future", reminderAt: Date.now() + 3600_000 }),
    ]);
    await t.ready;
    await t.tick();
    const cards = t.cards();
    const overdue = cards.find((c) => c.textContent?.includes("Late"));
    expect(overdue?.querySelector(".reminder")?.classList.contains("is-overdue")).toBe(true);
    const future = cards.find((c) => c.textContent?.includes("Future"));
    expect(future?.querySelector(".reminder")?.classList.contains("is-overdue")).toBe(false);
    overdue!.click();
    await t.tick();
    const input = t.app.querySelector<HTMLInputElement>(".editor-reminder")!;
    expect(input.value).not.toBe("");
    input.value = "";
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.tick();
    await t.tick();
    expect((await t.store.get("1"))?.reminderAt).toBeNull();
    const again = t.cards().find((c) => c.textContent?.includes("Late"));
    expect(again?.querySelector(".reminder")).toBeNull();
    t.cleanup();
  });
});
