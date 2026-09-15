// Lane A: create/edit dialog. `open(note | null)` shows it (null = new
// note); Save emits `note-save` with a title/body/color draft, Cancel (or
// Esc, or overlay click) emits `note-cancel`. <keeps-app> owns ids,
// timestamps, and persistence.
//
// Toolbar: undo/redo walk a snapshot history of the two text fields
// (native Ctrl/Cmd+Z still works too); the list button toggles a plain
// "- [ ] "/ "- [x] " marker on the current body line. Markers stay plain
// text so the frozen Note contract is untouched.
import { NOTE_COLORS, NOTE_LIMITS, type Attachment, type ChecklistItem } from "../../shared/note.js";
import { escapeHtml } from "./html.js";
import { buzz } from "./haptics.js";
import { processImageFile } from "./images.js";

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
}

const HISTORY_LIMIT = 100;

export class NoteEditor extends HTMLElement {
  private history: Snapshot[] = [];
  private cursor = -1;
  /** Checklist rows while the dialog is open; null = plain text mode. */
  private items: ChecklistItem[] | null = null;
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
        this.push();
      }
      if (target.classList.contains("check-text")) {
        const id = (target as HTMLElement).dataset["checkId"] ?? "";
        const item = this.items?.find((i) => i.id === id);
        if (item) item.text = (target as HTMLInputElement).value;
      }
    });
    this.addEventListener("change", (event) => {
      const target = event.target as HTMLElement;
      if (target.classList.contains("check-toggle")) {
        const id = (target as HTMLElement).dataset["checkId"] ?? "";
        const item = this.items?.find((i) => i.id === id);
        if (item) item.checked = (target as HTMLInputElement).checked;
        return;
      }
      if (target.classList.contains("editor-file")) {
        void this.stageFiles((target as HTMLInputElement).files);
        (target as HTMLInputElement).value = "";
      }
    });
    this.addEventListener("keydown", (event) => {
      if (event.key === "Escape") this.close(false);
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") this.close(true);
    });
  }

  open(draft: NoteDraft | null, labels: LabelOption[] = []): void {
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
    this.innerHTML = `
      <div class="editor-overlay" data-close>
        <div class="editor" role="dialog" aria-modal="true">
          <input class="editor-title" placeholder="Title" value="${escapeHtml(source.title)}" />
          <div class="editor-body-region"></div>
          ${
            labels.length > 0
              ? `<div class="editor-labels" role="group" aria-label="Labels">
                  ${labels
                    .map(
                      (l) =>
                        `<label class="editor-label"><input type="checkbox" class="label-check" value="${escapeHtml(l.id)}"${selected.has(l.id) ? " checked" : ""} />${escapeHtml(l.name)}</label>`,
                    )
                    .join("")}
                </div>`
              : ""
          }
          <div class="editor-attachments"></div>
          <div class="editor-error" role="alert" hidden></div>
          <div class="editor-reminder-row">
            <label>Remind me <input type="datetime-local" class="editor-reminder" value="${toReminderInput(source.reminderAt)}" /></label>
            <label>Repeat
              <select class="editor-repeat" aria-label="Repeat">
                <option value=""${source.repeat ? "" : " selected"}>Never</option>
                <option value="daily"${source.repeat === "daily" ? " selected" : ""}>Daily</option>
                <option value="weekly"${source.repeat === "weekly" ? " selected" : ""}>Weekly</option>
              </select>
            </label>
          </div>
          <div class="editor-tools" role="toolbar" aria-label="Edit tools">
            <button data-action="undo" aria-label="Undo">Undo</button>
            <button data-action="redo" aria-label="Redo">Redo</button>
            <button data-action="bold" aria-label="Bold">B</button>
            <button data-action="italic" aria-label="Italic">I</button>
            <button data-action="list" aria-label="Toggle checklist">List</button>
            <button data-action="checklist-mode" aria-label="Structured checklist">Checklist</button>
            <button data-action="photo" aria-label="Attach photo">Photo</button>
            <button data-action="draw" aria-label="Draw">Draw</button>
            <input type="file" class="editor-file" accept="image/*" multiple hidden />
          </div>
          <div class="editor-row">
            <select class="editor-color" aria-label="Color">
              ${NOTE_COLORS.map(
                (c) => `<option value="${c}"${c === source.color ? " selected" : ""}>${c}</option>`,
              ).join("")}
            </select>
            <span class="editor-spacer"></span>
            <button data-action="cancel">Close</button>
            <button data-action="save">Save</button>
          </div>
        </div>
      </div>`;
    this.paintBodyRegion(source.body);
    this.paintAttachments();
    this.push();
    this.querySelector<HTMLInputElement>(".editor-title")?.focus();
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
    return { title: title?.value ?? "", body: body?.value ?? "" };
  }

  private push(): void {
    const next = this.snapshot();
    const current = this.history[this.cursor];
    if (current && current.title === next.title && current.body === next.body) {
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
    if (body) {
      body.value = snap.body;
      body.focus();
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
      region.innerHTML = `<textarea class="editor-body" placeholder="Take a note...">${escapeHtml(fallbackBody)}</textarea>`;
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
  }

  private addItem(): void {
    if (this.items === null || this.items.length >= NOTE_LIMITS.maxChecklistItems) return;
    this.items.push({ id: crypto.randomUUID(), text: "", checked: false });
    this.paintBodyRegion("");
    this.querySelectorAll<HTMLInputElement>(".check-text")[
      this.items.length - 1
    ]?.focus();
  }

  private deleteItem(id: string): void {
    if (this.items === null) return;
    this.items = this.items.filter((i) => i.id !== id);
    this.paintBodyRegion("");
  }

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
          <img src="${escapeHtml(a.thumbUrl)}" alt="${escapeHtml(a.name)}" data-attach-view="${escapeHtml(a.id)}" />
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

  private async stageFiles(files: FileList | null): Promise<void> {
    if (!files || files.length === 0) return;
    this.clearError();
    for (const file of [...files].slice(0, NOTE_LIMITS.maxAttachments - this.staged.length)) {
      const attachment = await processImageFile(file);
      if (!attachment) {
        this.fail(`"${file.name}" is not a supported image or is too large.`);
        continue;
      }
      this.staged.push(attachment);
    }
    if (this.staged.length >= NOTE_LIMITS.maxAttachments && (files?.length ?? 0) > 0) {
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

  private close(save: boolean): void {
    if (save) {
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
        this.querySelector<HTMLSelectElement>(".editor-color")?.value ?? "default";
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
      this.dispatchEvent(
        new CustomEvent("note-save", { bubbles: true, composed: true, detail: { title, body, color, labelIds, reminderAt, repeat, checklist, attachments } satisfies NoteDraft }),
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
