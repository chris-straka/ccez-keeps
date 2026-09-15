// Lane A: single note card. Light DOM (flat, per plan — no per-card
// shadow roots at list scale). Renders from `.note`; user actions bubble
// as `card-action` CustomEvents { id, kind } for <keeps-app> to handle.
import type { Note } from "../../shared/note.js";
import { escapeHtml } from "./html.js";
import { renderBody } from "./markdown.js";

export type CardActionKind =
  | "open"
  | "pin"
  | "archive"
  | "delete"
  | "restore"
  | "delete-forever";

/** Overdue when the reminder time passed and the note is still live. */
export function isOverdue(note: Note, now: number = Date.now()): boolean {
  return (
    note.reminderAt !== null &&
    Number.isFinite(note.reminderAt) &&
    (note.reminderAt as number) < now &&
    !note.deleted
  );
}

export function formatReminder(ts: number): string {
  if (!Number.isFinite(ts)) return "";
  return new Date(ts).toLocaleString();
}

const CHECKLIST_PREVIEW_LIMIT = 5;
const ATTACHMENT_PREVIEW_LIMIT = 3;

/** Read-only item rows for the card; "" when the note is not a checklist. */
export function renderChecklistPreview(
  checklist: Note["checklist"],
): string {
  if (!checklist) return "";
  const rows = checklist.slice(0, CHECKLIST_PREVIEW_LIMIT).map(
    (item) => `
      <div class="check-row is-preview${item.checked ? " is-checked" : ""}">
        <span class="check" aria-hidden="true">${item.checked ? "☑" : "☐"}</span>
        <span class="check-preview-text">${escapeHtml(item.text) || "&nbsp;"}</span>
      </div>`,
  );
  const extra = checklist.length - CHECKLIST_PREVIEW_LIMIT;
  return `<div class="card-checklist">${rows.join("")}${
    extra > 0 ? `<div class="check-more">+${extra} more</div>` : ""
  }</div>`;
}

/** Thumbnail strip for the card; "" when there are no attachments. */
export function renderAttachmentPreview(attachments: Note["attachments"]): string {
  const list = attachments ?? [];
  if (list.length === 0) return "";
  const thumbs = list.slice(0, ATTACHMENT_PREVIEW_LIMIT).map(
    (a) =>
      `<img class="card-thumb" src="${escapeHtml(a.thumbUrl)}" alt="${escapeHtml(a.name)}" loading="lazy" />`,
  );
  const extra = list.length - ATTACHMENT_PREVIEW_LIMIT;
  return `<div class="card-thumbs">${thumbs.join("")}${
    extra > 0 ? `<span class="thumb-more">+${extra}</span>` : ""
  }</div>`;
}

export class NoteCard extends HTMLElement {
  private current: Note | undefined;
  private names: Record<string, string> = {};
  private colors: Record<string, string> = {};

  set note(value: Note) {
    this.current = value;
    this.render();
  }

  get note(): Note | undefined {
    return this.current;
  }

  set labelNames(value: Record<string, string>) {
    this.names = value ?? {};
    this.render();
  }

  set labelColors(value: Record<string, string>) {
    this.colors = value ?? {};
    this.render();
  }

  connectedCallback(): void {
    this.addEventListener("click", (event) => this.onClick(event));
    this.render();
  }

  private emit(kind: CardActionKind): void {
    if (!this.current) return;
    this.dispatchEvent(
      new CustomEvent("card-action", {
        bubbles: true,
        composed: true,
        detail: { id: this.current.id, kind },
      }),
    );
  }

  private onClick(event: MouseEvent): void {
    const target = (event.target as HTMLElement).closest("[data-action]");
    if (target) {
      event.stopPropagation();
      this.emit((target as HTMLElement).dataset["action"] as CardActionKind);
      return;
    }
    this.emit("open");
  }

  private render(): void {
    const note = this.current;
    if (!note) {
      this.innerHTML = "";
      return;
    }
    this.dataset["color"] = note.color;
    if (note.pinned) this.setAttribute("data-pinned", "");
    else this.removeAttribute("data-pinned");
    const inTrash = note.deleted;
    const chips = (note.labelIds ?? [])
      .map(
        (id) =>
          `<span class="label-chip" data-color="${escapeHtml(this.colors[id] ?? "default")}">${escapeHtml(this.names[id] ?? id)}</span>`,
      )
      .join("");
    const repeatSuffix =
      note.repeat === "daily" ? " • Daily" : note.repeat === "weekly" ? " • Weekly" : "";
    const reminder =
      note.reminderAt === null
        ? ""
        : `<div class="reminder${isOverdue(note) ? " is-overdue" : ""}">Reminds ${escapeHtml(formatReminder(note.reminderAt))}${repeatSuffix}</div>`;
    const checklist = renderChecklistPreview(note.checklist);
    const attachments = renderAttachmentPreview(note.attachments);
    this.innerHTML = `
      <div class="card-title">${escapeHtml(note.title) || "&nbsp;"}</div>
      ${attachments}
      ${checklist || `<div class="card-body">${renderBody(note.body)}</div>`}
      ${chips ? `<div class="label-chips">${chips}</div>` : ""}
      ${reminder}
      <div class="card-actions">
        ${
          inTrash
            ? `<button data-action="restore" title="Restore">Restore</button>
               <button data-action="delete-forever" title="Delete forever">Delete</button>`
            : `<button data-action="pin" title="${note.pinned ? "Unpin" : "Pin"}">${note.pinned ? "Unpin" : "Pin"}</button>
               <button data-action="archive" title="${note.archived ? "Unarchive" : "Archive"}">${note.archived ? "Unarchive" : "Archive"}</button>
               <button data-action="delete" title="Delete">Delete</button>`
        }
      </div>`;
  }
}

if (typeof customElements !== "undefined" && !customElements.get("note-card")) {
  customElements.define("note-card", NoteCard);
}
