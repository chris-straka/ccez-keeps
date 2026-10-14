// Pasting an image attaches it: in the editor (new or existing note) and
// in the inline composer, which hands the draft to the editor. happy-dom
// has no canvas, so the downscale step is mocked; everything else is real.
import { describe, expect, mock, test } from "bun:test";
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

const real = await import("../web/components/images.js");
const processed: string[] = [];
mock.module("../web/components/images.js", () => ({
  ...real,
  processImageFile: async (file: File) => {
    processed.push(file.name);
    return {
      id: `att-${file.name}`,
      name: file.name,
      mime: "image/jpeg",
      size: 4,
      dataUrl: "data:image/jpeg;base64,AAAA",
      thumbUrl: "data:image/jpeg;base64,AAAA",
    };
  },
}));

import type { KeepsApp } from "../web/components/keeps-app.js";
await import("../web/components/keeps-app.js");
await import("../web/components/note-card.js");
await import("../web/components/note-editor.js");
await import("../web/components/drawing-dialog.js");
const { MemoryStore } = await import("../web/store/memory-store.js");
const { newNote } = await import("../shared/note.js");
import type { Note } from "../shared/note.js";

function mount(initial: Note[] = []) {
  (globalThis as unknown as Record<string, unknown>)["fetch"] = () => {
    throw new Error("components must not fetch");
  };
  processed.length = 0;
  const store = new MemoryStore(initial);
  window.location.hash = "";
  const app = document.createElement("keeps-app") as KeepsApp;
  document.body.appendChild(app);
  const ready = app.connect(store, { schedulePush: () => {}, deleteForever: async () => {} });
  const tick = () => new Promise((r) => setTimeout(r, 0));
  const settle = async () => {
    for (let i = 0; i < 5; i += 1) await tick();
  };
  const cleanup = () => {
    app.remove();
    document.body.innerHTML = "";
  };
  return { store, app, ready, settle, cleanup };
}

/** A paste event carrying the given files (and optionally plain text). */
function paste(target: Element, files: File[], text?: string): Event {
  const event = new window.Event("paste", { bubbles: true, cancelable: true }) as unknown as Event;
  Object.defineProperty(event, "clipboardData", {
    value: {
      files,
      items: [],
      types: text === undefined ? ["Files"] : ["text/plain", "Files"],
      getData: () => text ?? "",
    },
  });
  target.dispatchEvent(event);
  return event;
}

const png = (name = "shot.png") => new File([new Uint8Array([1, 2, 3])], name, { type: "image/png" });

describe("paste an image", () => {
  test("into a new note's editor attaches it and saves with the note", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>("[data-new]")!.click();
    await t.settle();
    const body = t.app.querySelector<HTMLTextAreaElement>(".editor-body")!;
    const event = paste(body, [png()]);
    await t.settle();
    expect(event.defaultPrevented).toBe(true);
    expect(processed).toEqual(["shot.png"]);
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(1);
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.settle();
    const [saved] = await t.store.all();
    expect(saved?.attachments.map((a) => a.name)).toEqual(["shot.png"]);
    t.cleanup();
  });

  test("into an existing note's title adds to its photos", async () => {
    const t = mount([newNote({ id: "1", title: "Trip", body: "", updatedAt: 1000 })]);
    await t.ready;
    await t.settle();
    (t.app.querySelector("note-card") as HTMLElement).click();
    await t.settle();
    paste(t.app.querySelector(".editor-title")!, [png("a.png"), png("b.png")]);
    await t.settle();
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.settle();
    expect((await t.store.get("1"))?.attachments.map((a) => a.name)).toEqual(["a.png", "b.png"]);
    t.cleanup();
  });

  test("a rich copy with text still pastes the text and attaches the image", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>("[data-new]")!.click();
    await t.settle();
    const event = paste(t.app.querySelector(".editor-body")!, [png()], "caption");
    await t.settle();
    expect(event.defaultPrevented).toBe(false);
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(1);
    t.cleanup();
  });

  test("plain text paste is left alone", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-composer="open"]')!.click();
    await t.settle();
    const event = paste(t.app.querySelector(".composer-body")!, [], "hello");
    await t.settle();
    expect(event.defaultPrevented).toBe(false);
    expect(t.app.querySelector(".editor")).toBeNull();
    expect(t.app.querySelector(".composer")).not.toBeNull();
    t.cleanup();
  });

  test("into the composer moves the draft into the editor with the image", async () => {
    const t = mount();
    await t.ready;
    t.app.querySelector<HTMLButtonElement>('[data-composer="open"]')!.click();
    await t.settle();
    t.app.querySelector<HTMLInputElement>(".composer-title")!.value = "Receipt";
    const body = t.app.querySelector<HTMLTextAreaElement>(".composer-body")!;
    body.value = "for taxes";
    const event = paste(body, [png("receipt.png")]);
    await t.settle();
    expect(event.defaultPrevented).toBe(true);
    expect(t.app.querySelector(".composer")).toBeNull();
    expect(t.app.querySelector<HTMLInputElement>(".editor-title")?.value).toBe("Receipt");
    expect(t.app.querySelector<HTMLTextAreaElement>(".editor-body")?.value).toBe("for taxes");
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(1);
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.settle();
    const [saved] = await t.store.all();
    expect(saved?.title).toBe("Receipt");
    expect(saved?.attachments.map((a) => a.name)).toEqual(["receipt.png"]);
    t.cleanup();
  });

  test("a pasted image survives a background re-render (sync landing)", async () => {
    const t = mount([newNote({ id: "x", title: "Other", body: "", updatedAt: 1000 })]);
    await t.ready;
    t.app.querySelector<HTMLButtonElement>("[data-new]")!.click();
    await t.settle();
    paste(t.app.querySelector(".editor-body")!, [png()]);
    await t.settle();
    // A pull lands while the dialog is open: the shell re-renders.
    await t.store.put(newNote({ id: "y", title: "From phone", body: "", updatedAt: 2000 }));
    await t.settle();
    expect(t.app.querySelector(".grid")?.textContent).toContain("From phone");
    expect(t.app.querySelectorAll(".attach-thumb")).toHaveLength(1);
    t.app.querySelector<HTMLButtonElement>('[data-action="save"]')!.click();
    await t.settle();
    const fresh = (await t.store.all()).find((n) => n.attachments.length > 0);
    expect(fresh?.attachments.map((a) => a.name)).toEqual(["shot.png"]);
    t.cleanup();
  });
});
