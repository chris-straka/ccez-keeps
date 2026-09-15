// Lane A: create/edit dialog. `open(note | null)` shows it (null = new
// note); Save emits `note-save` with a title/body/color draft, Cancel (or
// Esc, or overlay click) emits `note-cancel`. <keeps-app> owns ids,
// timestamps, and persistence.
//
// Toolbar: undo/redo walk a snapshot history of the two text fields
// (native Ctrl/Cmd+Z still works too); the list button toggles a plain
// "- [ ] "/ "- [x] " marker on the current body line. Markers stay plain
// text so the frozen Note contract is untouched.
import { NOTE_COLORS } from "../../shared/note.js";
import { escapeHtml } from "./html.js";
import { buzz } from "./haptics.js";

export interface NoteDraft {
  title: string;
  body: string;
  color: string;
  labelIds?: string[];
  reminderAt?: number | null;
  repeat?: "daily" | "weekly" | null;
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

  connectedCallback(): void {
    this.addEventListener("click", (event) => {
      const target = event.target as HTMLElement;
      if (target.dataset["close"] !== undefined) this.close(false);
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
    this.innerHTML = `
      <div class="editor-overlay" data-close>
        <div class="editor" role="dialog" aria-modal="true">
          <input class="editor-title" placeholder="Title" value="${escapeHtml(source.title)}" />
          <textarea class="editor-body" placeholder="Take a note...">${escapeHtml(source.body)}</textarea>
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
            <button data-action="draw" aria-label="Draw">Draw</button>
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
      const body = this.querySelector<HTMLTextAreaElement>(".editor-body")?.value ?? "";
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
        new CustomEvent("note-save", { bubbles: true, composed: true, detail: { title, body, color, labelIds, reminderAt, repeat } satisfies NoteDraft }),
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
