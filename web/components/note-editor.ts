// Lane A: create/edit dialog. `open(note | null)` shows it (null = new
// note); Save emits `note-save` with a title/body/color draft, Cancel (or
// Esc, or overlay click) emits `note-cancel`. <keeps-app> owns ids,
// timestamps, and persistence.
//
// Layout follows Keep's editor: photos on top, borderless title and body,
// chips for labels and the reminder, and one footer of icon tools with
// Close/Save. Reminder, colour and labels open small panels above the
// footer; the dialog takes the note's colour as it's picked.
//
// Toolbar: undo/redo walk a snapshot history of the two text fields
// (native Ctrl/Cmd+Z still works too); the list button toggles a plain
// "- [ ] "/ "- [x] " marker on the current body line. Markers stay plain
// text so the frozen Note contract is untouched.
import { NOTE_COLORS, NOTE_LIMITS, type Attachment, type ChecklistItem } from "../../shared/note.js";
import { escapeHtml } from "./html.js";
import { buzz } from "./haptics.js";
import { clipboardImages, processImageFile } from "./images.js";

export interface NoteDraft {
  title: string;
  body: string;
  color: string;
  labelIds?: string[];
  reminderAt?: number | null;
  repeat?: "daily" | "weekly" | null;
  checklist?: ChecklistItem[] | null;
  attachments?: Attachment[];
}

export interface LabelOption {
  id: string;
  name: string;
}

/** Epoch ms -> datetime-local value (local time). Null -> "". */
export function toReminderInput(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "";
  const d = new Date(value);
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local value -> epoch ms. "" -> null (clears the reminder). */
export function parseReminderInput(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  const ms = new Date(trimmed).getTime();
  return Number.isFinite(ms) ? ms : null;
}

interface Snapshot {
  title: string;
  body: string;
  /** Deep copy of the checklist rows; null = plain text mode. */
  items: ChecklistItem[] | null;
}

const HISTORY_LIMIT = 100;

const svg = (body: string): string =>
  `<svg viewBox="0 0 16 16" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;

const TOOL_ICONS = {
  remind: svg('<path d="M8 2a4 4 0 0 1 4 4c0 3 1 4 1 4H3s1-1 1-4a4 4 0 0 1 4-4z"/><path d="M6.5 12.5a1.5 1.5 0 0 0 3 0"/>'),
  color: svg('<path d="M8 1.8a6.2 6.2 0 1 0 0 12.4c.9 0 1.4-.6 1.4-1.3 0-.9-.7-1.1-.7-1.9 0-.7.6-1.2 1.3-1.2h1.6a2.8 2.8 0 0 0 2.8-2.8C14.4 4.2 11.5 1.8 8 1.8z"/><circle cx="4.8" cy="7.2" r=".8" fill="currentColor"/><circle cx="7" cy="4.6" r=".8" fill="currentColor"/><circle cx="10.2" cy="4.8" r=".8" fill="currentColor"/>'),
  photo: svg('<rect x="1.8" y="2.8" width="12.4" height="10.4" rx="1.5"/><circle cx="5.5" cy="6.3" r="1.1"/><path d="M2 11.5l3.6-3.4 2.6 2.4 2.2-2 3.6 3.2"/>'),
  draw: svg('<path d="M10.8 2.6l2.6 2.6-7.6 7.6-3.3.7.7-3.3z"/><path d="M9.4 4l2.6 2.6"/>'),
  checklist: svg('<rect x="2" y="2" width="12" height="12" rx="2"/><path d="M5 8.2l2 2 4-4.4"/>'),
  list: svg('<rect x="1.8" y="3" width="3.4" height="3.4" rx=".6"/><rect x="1.8" y="9.6" width="3.4" height="3.4" rx=".6"/><path d="M7.5 4.7h6.5M7.5 11.3h6.5"/>'),
  labels: svg('<path d="M2.5 2.5h4.5l6.5 6.5-4.5 4.5-6.5-6.5z"/><circle cx="5.6" cy="5.6" r=".9"/>'),
  undo: svg('<path d="M5.5 3.5L2.5 6.5l3 3"/><path d="M2.8 6.5h6.7a3.5 3.5 0 0 1 0 7H7"/>'),
  redo: svg('<path d="M10.5 3.5l3 3-3 3"/><path d="M13.2 6.5H6.5a3.5 3.5 0 0 0 0 7H9"/>'),
};

/** "Mon, Oct 20, 10:00" for the reminder chip. */
function reminderLabel(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Grow the body to its content like Keep (CSS max-height caps it). */
function fitBody(el: HTMLTextAreaElement): void {
  el.style.height = "auto";
  el.style.height = `${el.scrollHeight + 2}px`;
}

export class NoteEditor extends HTMLElement {
  private history: Snapshot[] = [];
  private cursor = -1;
  /** Checklist rows while the dialog is open; null = plain text mode. */
  private items: ChecklistItem[] | null = null;
  private bodyPlaceholder = "Take a note...";
  /** Attachments staged for save (start from the opened note's). */
  private staged: Attachment[] = [];
  private editorError = "";

  connectedCallback(): void {
    this.addEventListener("click", (event) => {
      const target = event.target as HTMLElement;
      if (target.dataset["close"] !== undefined) this.close(false);
      const del = target.closest("[data-check-del]") as HTMLElement | null;
      if (del) {
        this.deleteItem(del.dataset["checkDel"] ?? "");
        buzz("tap");
        return;
      }
      const view = target.closest("[data-attach-view]") as HTMLElement | null;
      if (view) {
        this.openViewer(view.dataset["attachView"] ?? "");
        return;
      }
      const unattach = target.closest("[data-attach-del]") as HTMLElement | null;
      if (unattach) {
        this.removeAttachment(unattach.dataset["attachDel"] ?? "");
        buzz("tap");
        return;
      }
      const action = target.closest("[data-action]") as HTMLElement | null;
      if (!action) return;
      switch (action.dataset["action"]) {
        case "save":
          this.close(true);
          break;
        case "cancel":
          this.close(false);
          break;
        case "panel":
          this.togglePanel(action.dataset["panel"] ?? "");
          break;
        case "color":
          this.pickColor(action.dataset["color"] ?? "default");
          buzz("tap");
          break;
        case "clear-reminder": {
          const input = this.querySelector<HTMLInputElement>(".editor-reminder");
          if (input) input.value = "";
          this.paintChips();
          break;
        }
        case "undo":
          this.step(-1);
          buzz("tap");
          break;
        case "redo":
          this.step(1);
          buzz("tap");
          break;
        case "list":
          this.toggleChecklist();
          buzz("tap");
          break;
        case "checklist-mode":
          this.toggleListMode();
          buzz("tap");
          break;
        case "check-add":
          this.addItem();
          buzz("tap");
          break;
        case "photo":
          this.querySelector<HTMLInputElement>(".editor-file")?.click();
          break;
        case "draw":
          buzz("tap");
          this.dispatchEvent(
            new CustomEvent("drawing-open", { bubbles: true, composed: true }),
          );
          break;
        case "bold":
          this.wrapSelection("**", "**");
          buzz("tap");
          break;
        case "italic":
          this.wrapSelection("*", "*");
          buzz("tap");
          break;
      }
    });
    this.addEventListener("input", (event) => {
      const target = event.target as HTMLElement;
      if (
        target.classList.contains("editor-title") ||
        target.classList.contains("editor-body")
      ) {
        if (target.classList.contains("editor-body")) fitBody(target as HTMLTextAreaElement);
        this.push();
      }
      if (target.classList.contains("check-text")) {
        const id = (target as HTMLElement).dataset["checkId"] ?? "";
        const item = this.items?.find((i) => i.id === id);
        if (item) {
          item.text = (target as HTMLInputElement).value;
          this.push();
        }
      }
    });
    this.addEventListener("change", (event) => {
      const target = event.target as HTMLElement;
      if (
        target.classList.contains("label-check") ||
        target.classList.contains("editor-reminder") ||
        target.classList.contains("editor-repeat")
      ) {
        this.paintChips();
        return;
      }
      if (target.classList.contains("check-toggle")) {
        const id = (target as HTMLElement).dataset["checkId"] ?? "";
        const item = this.items?.find((i) => i.id === id);
        if (item) {
          item.checked = (target as HTMLInputElement).checked;
          this.push();
        }
        return;
      }
      if (target.classList.contains("editor-file")) {
        void this.attachFiles([...((target as HTMLInputElement).files ?? [])]);
        (target as HTMLInputElement).value = "";
      }
    });
    // Pasting a screenshot or copied image attaches it, wherever the
    // caret is. Rich copies that also carry text still paste the text.
    this.addEventListener("paste", (event) => {
      const data = (event as ClipboardEvent).clipboardData;
      const images = clipboardImages(data);
      if (images.length === 0) return;
      if (!data?.types.includes("text/plain")) event.preventDefault();
      void this.attachFiles(images);
    });
    this.addEventListener("keydown", (event) => {
      if (event.key === "Escape") this.close(false);
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") this.close(true);
      // Desktop undo runs through the dialog history (checklist edits
      // included); mobile keeps the toolbar buttons instead.
      if ((event.metaKey || event.ctrlKey) && !event.altKey) {
        const key = event.key.toLowerCase();
        if (key === "z" && !event.shiftKey) {
          event.preventDefault();
          this.step(-1);
          buzz("tap");
        } else if (key === "z" || key === "y") {
          event.preventDefault();
          this.step(1);
          buzz("tap");
        }
      }
    });
  }

  open(
    draft: NoteDraft | null,
    labels: LabelOption[] = [],
    opts?: { focusReminder?: boolean; idea?: boolean },
  ): void {
    // Idea mode (the Idea button): the body is focused with the phone
    // keyboard up, so the keyboard's mic dictates straight into it.
    this.bodyPlaceholder = opts?.idea ? "Say or type the idea…" : "Take a note...";
    const source = draft ?? { title: "", body: "", color: "default" };
    const selected = new Set(source.labelIds ?? []);
    this.history = [];
    this.cursor = -1;
    this.items =
      source.checklist === undefined || source.checklist === null
        ? null
        : source.checklist.map((i) => ({ ...i }));
    this.staged = (source.attachments ?? []).map((a) => ({ ...a }));
    this.editorError = "";
    const color = (NOTE_COLORS as readonly string[]).includes(source.color) ? source.color : "default";
    const tool = (action: string, label: string, icon: string, extra = ""): string =>
      `<button type="button" class="tool" data-action="${action}" aria-label="${label}" title="${label}"${extra}>${icon}</button>`;
    const panelTool = (panel: string, label: string, icon: string): string =>
      tool("panel", label, icon, ` data-panel="${panel}" aria-expanded="false"`);
    this.innerHTML = `
      <div class="editor-overlay note-overlay" data-close>
        <div class="editor note-dialog" role="dialog" aria-modal="true" aria-label="${draft ? "Edit note" : "New note"}" data-color="${color}">
          <div class="editor-scroll">
            <div class="editor-attachments"></div>
            <input class="editor-title" placeholder="Title" value="${escapeHtml(source.title)}" />
            <div class="editor-body-region"></div>
            <div class="editor-chips"></div>
            <div class="editor-error" role="alert" hidden></div>
          </div>
          <div class="editor-panel editor-reminder-row" data-panel-body="remind" hidden>
            <label>Remind me <input type="datetime-local" class="editor-reminder" value="${toReminderInput(source.reminderAt)}" /></label>
            <label>Repeat
              <select class="editor-repeat" aria-label="Repeat">
                <option value=""${source.repeat ? "" : " selected"}>Never</option>
                <option value="daily"${source.repeat === "daily" ? " selected" : ""}>Daily</option>
                <option value="weekly"${source.repeat === "weekly" ? " selected" : ""}>Weekly</option>
              </select>
            </label>
            <button type="button" class="link" data-action="clear-reminder">Clear</button>
          </div>
          <div class="editor-panel editor-palette" data-panel-body="color" role="radiogroup" aria-label="Color" hidden>
            ${NOTE_COLORS.map(
              (c) =>
                `<button type="button" class="color-swatch" data-action="color" data-color="${c}" role="radio" aria-checked="${c === color}" aria-label="${c}" title="${c}"></button>`,
            ).join("")}
            <input type="hidden" class="editor-color" value="${color}" />
          </div>
          ${
            labels.length > 0
              ? `<div class="editor-panel editor-labels" data-panel-body="labels" role="group" aria-label="Labels" hidden>
                  ${labels
                    .map(
                      (l) =>
                        `<label class="editor-label"><input type="checkbox" class="label-check" value="${escapeHtml(l.id)}"${selected.has(l.id) ? " checked" : ""} />${escapeHtml(l.name)}</label>`,
                    )
                    .join("")}
                </div>`
              : ""
          }
          <div class="editor-foot">
            <div class="editor-tools" role="toolbar" aria-label="Edit tools">
              ${panelTool("remind", "Remind me", TOOL_ICONS.remind)}
              ${panelTool("color", "Background colour", TOOL_ICONS.color)}
              ${tool("photo", "Attach photo (or paste one)", TOOL_ICONS.photo)}
              ${tool("draw", "Draw", TOOL_ICONS.draw)}
              ${tool("checklist-mode", "Checklist", TOOL_ICONS.checklist)}
              ${tool("list", "Toggle checkbox on this line", TOOL_ICONS.list)}
              ${labels.length > 0 ? panelTool("labels", "Labels", TOOL_ICONS.labels) : ""}
              ${tool("bold", "Bold", "<b>B</b>")}
              ${tool("italic", "Italic", "<i>I</i>")}
              ${tool("undo", "Undo", TOOL_ICONS.undo)}
              ${tool("redo", "Redo", TOOL_ICONS.redo)}
              <input type="file" class="editor-file" accept="image/*" multiple hidden />
            </div>
            <div class="editor-row">
              <button type="button" data-action="cancel" title="Close without saving (Esc)">Close</button>
              <button type="button" class="primary" data-action="save" title="Save (Ctrl/⌘ Enter)">Save</button>
            </div>
          </div>
        </div>
      </div>`;
    this.paintBodyRegion(source.body);
    this.paintAttachments();
    this.paintChips();
    this.push();
    if (opts?.focusReminder) {
      this.togglePanel("remind", true);
      this.querySelector<HTMLInputElement>(".editor-reminder")?.focus();
    } else if (opts?.idea) {
      this.querySelector<HTMLTextAreaElement>(".editor-body")?.focus();
    } else {
      this.querySelector<HTMLInputElement>(".editor-title")?.focus();
    }
  }

  /** Show one footer panel (reminder, colour, labels); others close. */
  private togglePanel(name: string, force?: boolean): void {
    for (const panel of this.querySelectorAll<HTMLElement>("[data-panel-body]")) {
      const open = panel.dataset["panelBody"] === name && (force ?? panel.hidden);
      panel.hidden = !open;
      this.querySelector(`.editor-tools [data-panel="${panel.dataset["panelBody"]}"]`)?.setAttribute(
        "aria-expanded",
        String(open),
      );
    }
  }

  private pickColor(color: string): void {
    const input = this.querySelector<HTMLInputElement>(".editor-color");
    if (input) input.value = color;
    const dialog = this.querySelector<HTMLElement>(".editor");
    if (dialog) dialog.dataset["color"] = color;
    for (const swatch of this.querySelectorAll<HTMLElement>(".color-swatch")) {
      swatch.setAttribute("aria-checked", String(swatch.dataset["color"] === color));
    }
  }

  /** Label and reminder chips under the body; a click opens their panel. */
  private paintChips(): void {
    const zone = this.querySelector(".editor-chips");
    if (!zone) return;
    const chips: string[] = [];
    const at = parseReminderInput(this.querySelector<HTMLInputElement>(".editor-reminder")?.value ?? "");
    if (at !== null) {
      const repeat = this.querySelector<HTMLSelectElement>(".editor-repeat")?.value;
      chips.push(
        `<button type="button" class="editor-chip" data-action="panel" data-panel="remind">${TOOL_ICONS.remind}<span>${escapeHtml(reminderLabel(at))}${repeat ? ` · ${escapeHtml(repeat)}` : ""}</span></button>`,
      );
    }
    for (const box of this.querySelectorAll<HTMLInputElement>(".label-check")) {
      if (!box.checked) continue;
      const name = box.parentElement?.textContent?.trim() ?? "";
      chips.push(
        `<button type="button" class="editor-chip" data-action="panel" data-panel="labels">${escapeHtml(name)}</button>`,
      );
    }
    zone.innerHTML = chips.join("");
  }

  private fields(): {
    title: HTMLInputElement | null;
    body: HTMLTextAreaElement | null;
  } {
    return {
      title: this.querySelector<HTMLInputElement>(".editor-title"),
      body: this.querySelector<HTMLTextAreaElement>(".editor-body"),
    };
  }

  private snapshot(): Snapshot {
    const { title, body } = this.fields();
    return {
      title: title?.value ?? "",
      body: body?.value ?? "",
      items: this.items === null ? null : this.items.map((i) => ({ ...i })),
    };
  }

  private sameItems(a: ChecklistItem[] | null, b: ChecklistItem[] | null): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
  }

  private push(): void {
    const next = this.snapshot();
    const current = this.history[this.cursor];
    if (
      current &&
      current.title === next.title &&
      current.body === next.body &&
      this.sameItems(current.items, next.items)
    ) {
      return;
    }
    this.history = [...this.history.slice(0, this.cursor + 1), next].slice(
      -HISTORY_LIMIT,
    );
    this.cursor = this.history.length - 1;
    this.paintTools();
  }

  private step(delta: -1 | 1): void {
    const next = this.cursor + delta;
    if (next < 0 || next >= this.history.length) return;
    this.cursor = next;
    const snap = this.history[this.cursor];
    if (!snap) return;
    const { title, body } = this.fields();
    if (title) title.value = snap.title;
    const modeChanged = (this.items === null) !== (snap.items === null);
    this.items = snap.items === null ? null : snap.items.map((i) => ({ ...i }));
    if (!modeChanged && body) {
      // Same text mode: keep the node (callers may hold a reference).
      body.value = snap.body;
      body.focus();
    } else {
      // New mode or list rows: the region repaint rebuilds the inputs.
      this.paintBodyRegion(snap.body);
      this.querySelector<HTMLInputElement>(".editor-body")?.focus();
    }
    this.paintTools();
  }

  private paintTools(): void {
    const undo = this.querySelector<HTMLButtonElement>('[data-action="undo"]');
    const redo = this.querySelector<HTMLButtonElement>('[data-action="redo"]');
    if (undo) undo.disabled = this.cursor <= 0;
    if (redo) redo.disabled = this.cursor >= this.history.length - 1;
  }

  /** Wrap the body selection (or caret) in markers for inline formatting. */
  private wrapSelection(before: string, after: string): void {
    const { body } = this.fields();
    if (!body) return;
    const start = body.selectionStart ?? body.value.length;
    const end = body.selectionEnd ?? body.value.length;
    const selected = body.value.slice(start, end);
    body.value =
      body.value.slice(0, start) + before + selected + after + body.value.slice(end);
    body.focus();
    if (selected.length === 0) {
      // No selection: drop the caret between the fresh markers.
      const caret = start + before.length;
      body.setSelectionRange(caret, caret);
    } else {
      // Keep the whole wrapped span selected for one-tap re-toggle.
      body.setSelectionRange(start, start + before.length + selected.length + after.length);
    }
    this.push();
  }

  /** Render the textarea or the structured item rows into the body region. */
  private paintBodyRegion(fallbackBody: string): void {
    const region = this.querySelector(".editor-body-region");
    if (!region) return;
    if (this.items === null) {
      region.innerHTML = `<textarea class="editor-body" placeholder="${escapeHtml(this.bodyPlaceholder)}" autocapitalize="sentences" spellcheck="true">${escapeHtml(fallbackBody)}</textarea>`;
      const body = region.querySelector<HTMLTextAreaElement>(".editor-body");
      // Size after layout: the dialog is only measurable once attached.
      if (body) globalThis.requestAnimationFrame?.(() => fitBody(body));
      return;
    }
    region.innerHTML = `
      <div class="editor-checklist" role="group" aria-label="Checklist">
        ${this.items
          .map(
            (item) => `
          <div class="check-row">
            <input type="checkbox" class="check-toggle" data-check-id="${escapeHtml(item.id)}"${item.checked ? " checked" : ""} aria-label="Done" />
            <input class="check-text" data-check-id="${escapeHtml(item.id)}" placeholder="List item" value="${escapeHtml(item.text)}" />
            <button data-check-del="${escapeHtml(item.id)}" aria-label="Delete item">×</button>
          </div>`,
          )
          .join("")}
        <button data-action="check-add">+ Add item</button>
      </div>`;
  }

  /** Plain lines become items (marker prefixes stripped); empty lines drop. */
  private toggleListMode(): void {
    if (this.items !== null) {
      const body = this.items.map((i) => i.text).join("\n");
      this.items = null;
      this.paintBodyRegion(body);
      this.push();
      return;
    }
    const current = this.querySelector<HTMLTextAreaElement>(".editor-body")?.value ?? "";
    const lines = current
      .split("\n")
      .map((l) => l.replace(/^\s*-\s*\[( |x)\]\s*/, "").trimEnd());
    const kept = lines.filter((l) => l.trim() !== "");
    this.items = (kept.length > 0 ? kept : [""]).map((text) => ({
      id: crypto.randomUUID(),
      text,
      checked: false,
    }));
    this.paintBodyRegion("");
    this.push();
  }

  private addItem(): void {
    if (this.items === null || this.items.length >= NOTE_LIMITS.maxChecklistItems) return;
    this.items.push({ id: crypto.randomUUID(), text: "", checked: false });
    this.paintBodyRegion("");
    this.push();
    this.querySelectorAll<HTMLInputElement>(".check-text")[
      this.items.length - 1
    ]?.focus();
  }

  private deleteItem(id: string): void {
    if (this.items === null) return;
    this.items = this.items.filter((i) => i.id !== id);
    this.paintBodyRegion("");
    this.push();
  }

  /** Full images, not thumbs: the dialog shows them edge to edge. */
  private paintAttachments(): void {
    const zone = this.querySelector(".editor-attachments");
    if (!zone) return;
    if (this.staged.length === 0) {
      zone.innerHTML = "";
      return;
    }
    zone.innerHTML = this.staged
      .map(
        (a) => `
        <figure class="attach-thumb">
          <img src="${escapeHtml(a.dataUrl)}" alt="${escapeHtml(a.name)}" data-attach-view="${escapeHtml(a.id)}" />
          <button data-attach-del="${escapeHtml(a.id)}" aria-label="Remove ${escapeHtml(a.name)}">×</button>
        </figure>`,
      )
      .join("");
  }

  private fail(message: string): void {
    this.editorError = message;
    const el = this.querySelector(".editor-error");
    if (!el) return;
    el.textContent = message;
    el.removeAttribute("hidden");
  }

  private clearError(): void {
    this.editorError = "";
    const el = this.querySelector(".editor-error");
    if (!el) return;
    el.textContent = "";
    el.setAttribute("hidden", "");
  }

  /** Downscale and stage image files (photo picker, paste, composer). */
  async attachFiles(files: File[]): Promise<void> {
    if (files.length === 0) return;
    this.clearError();
    for (const file of files.slice(0, NOTE_LIMITS.maxAttachments - this.staged.length)) {
      const attachment = await processImageFile(file);
      if (!attachment) {
        this.fail(`"${file.name}" is not a supported image or is too large.`);
        continue;
      }
      this.staged.push(attachment);
    }
    if (this.staged.length >= NOTE_LIMITS.maxAttachments) {
      this.fail(`At most ${NOTE_LIMITS.maxAttachments} photos per note.`);
    }
    this.paintAttachments();
    buzz("confirm");
  }

  private removeAttachment(id: string): void {
    this.staged = this.staged.filter((a) => a.id !== id);
    this.paintAttachments();
  }

  /** Full-size overlay for one staged attachment. */
  private openViewer(id: string): void {
    const found = this.staged.find((a) => a.id === id);
    if (!found) return;
    const overlay = document.createElement("div");
    overlay.className = "viewer-overlay";
    overlay.setAttribute("data-close-viewer", "");
    overlay.innerHTML = `
      <figure class="viewer">
        <img src="${escapeHtml(found.dataUrl)}" alt="${escapeHtml(found.name)}" />
        <figcaption>${escapeHtml(found.name)}</figcaption>
      </figure>`;
    overlay.addEventListener("click", () => overlay.remove());
    this.querySelector(".editor")?.appendChild(overlay);
  }

  private toggleChecklist(): void {
    const { body } = this.fields();
    if (!body) return;
    const value = body.value;
    const caret = body.selectionStart ?? value.length;
    const lineStart = value.lastIndexOf("\n", caret - 1) + 1;
    const lineEnd = value.indexOf("\n", caret);
    const end = lineEnd === -1 ? value.length : lineEnd;
    const line = value.slice(lineStart, end);
    let next: string;
    if (line.startsWith("- [ ] ")) next = `- [x] ${line.slice(6)}`;
    else if (line.startsWith("- [x] ")) next = `- [ ] ${line.slice(6)}`;
    else next = `- [ ] ${line}`;
    body.value = value.slice(0, lineStart) + next + value.slice(end);
    const offset = lineStart + next.length;
    body.focus();
    body.setSelectionRange(offset, offset);
    this.push();
  }

  /** What Save would store right now (also stashed across re-renders). */
  draft(): NoteDraft {
    const title = this.querySelector<HTMLInputElement>(".editor-title")?.value ?? "";
    // Checklist mode: items are the source of truth; body carries the
    // joined lines so older clients still show the text.
    const checklist =
      this.items === null
        ? null
        : this.items
            .filter((i) => i.text.trim() !== "")
            .slice(0, NOTE_LIMITS.maxChecklistItems)
            .map((i) => ({ ...i, text: i.text.slice(0, NOTE_LIMITS.maxChecklistText) }));
    const body =
      checklist === null
        ? (this.querySelector<HTMLTextAreaElement>(".editor-body")?.value ?? "")
        : checklist.map((i) => i.text).join("\n");
    const attachments = this.staged.slice(0, NOTE_LIMITS.maxAttachments);
    const color =
      this.querySelector<HTMLInputElement>(".editor-color")?.value ?? "default";
    const labelIds = [...this.querySelectorAll<HTMLInputElement>(".label-check")]
      .filter((box) => box.checked)
      .map((box) => box.value);
    const reminderAt = parseReminderInput(
      this.querySelector<HTMLInputElement>(".editor-reminder")?.value ?? "",
    );
    // Repeat only exists attached to a reminder.
    const repeatValue =
      this.querySelector<HTMLSelectElement>(".editor-repeat")?.value ?? "";
    const repeat =
      reminderAt === null
        ? null
        : repeatValue === "daily" || repeatValue === "weekly"
          ? repeatValue
          : null;
    return { title, body, color, labelIds, reminderAt, repeat, checklist, attachments };
  }

  private close(save: boolean): void {
    if (save) {
      this.dispatchEvent(
        new CustomEvent("note-save", { bubbles: true, composed: true, detail: this.draft() }),
      );
    } else {
      this.dispatchEvent(new CustomEvent("note-cancel", { bubbles: true, composed: true }));
    }
    this.innerHTML = "";
  }
}

if (typeof customElements !== "undefined" && !customElements.get("note-editor")) {
  customElements.define("note-editor", NoteEditor);
}
