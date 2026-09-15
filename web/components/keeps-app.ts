// Lane A: app shell. Owns view/search state and wires Store + SyncPort
// to the card grid, inline composer, and editor dialog. Renders from the
// local store only — it never touches the network (sync lives in lane B).
import { newNote, type Note } from "../../shared/note.js";
import type { Drawing } from "../../shared/drawing.js";
import type { Store, NoteView, SyncStatus } from "../store/types.js";
import type { DrawingStore } from "../store/drawings.js";
import { escapeHtml } from "./html.js";
import { buzz } from "./haptics.js";
import { DevicesClient, type DeviceInfo } from "./devices.js";
import type { LabelOption, NoteDraft } from "./note-editor.js";
import { parseReminderInput } from "./note-editor.js";
import type { DrawingDraft } from "./drawing-dialog.js";
import type { CardActionKind } from "./note-card.js";
import { LabelsStore } from "../store/labels.js";
import type { Label } from "../../shared/label.js";

/** Minimal sync surface the shell needs (satisfied by SyncEngine). */
export interface SyncPort {
  schedulePush(): void;
  deleteForever(id: string): Promise<void>;
}

const ICONS: Record<NoteView, string> = {
  notes:
    '<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="3" y="1.5" width="10" height="13" rx="1.5"/><line x1="5.5" y1="5.5" x2="10.5" y2="5.5"/><line x1="5.5" y1="8.5" x2="10.5" y2="8.5"/><line x1="5.5" y1="11.5" x2="8.5" y2="11.5"/></svg>',
  archive:
    '<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="1.5" y="3" width="13" height="10" rx="1"/><line x1="1.5" y1="6" x2="14.5" y2="6"/><line x1="6.5" y1="9" x2="9.5" y2="9"/></svg>',
  trash:
    '<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M2.5 4h11M6.5 4V2.5h3V4M4 4l.7 9.5h6.6L12 4"/><line x1="6.5" y1="6.5" x2="6.5" y2="11"/><line x1="9.5" y1="6.5" x2="9.5" y2="11"/></svg>',
};

const NAV_ICON =
  '<svg viewBox="0 0 16 16" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><line x1="2" y1="4" x2="14" y2="4"/><line x1="2" y1="8" x2="14" y2="8"/><line x1="2" y1="12" x2="14" y2="12"/></svg>';

const VIEWS: { id: NoteView; label: string }[] = [
  { id: "notes", label: "Notes" },
  { id: "archive", label: "Archive" },
  { id: "trash", label: "Trash" },
];

const DEVICE_ICON =
  '<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="4.5" y="1.5" width="7" height="13" rx="1.5"/><line x1="7" y1="12.5" x2="9" y2="12.5"/></svg>';

export type Theme = "light" | "dark";

const THEME_ICONS: Record<Theme, string> = {
  // Shown is the theme you will get: sun in the dark, moon in the light.
  light:
    '<svg viewBox="0 0 16 16" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><circle cx="8" cy="8" r="3.5"/><line x1="8" y1="1" x2="8" y2="2.5"/><line x1="8" y1="13.5" x2="8" y2="15"/><line x1="1" y1="8" x2="2.5" y2="8"/><line x1="13.5" y1="8" x2="15" y2="8"/><line x1="3" y1="3" x2="4" y2="4"/><line x1="12" y1="12" x2="13" y2="13"/><line x1="3" y1="13" x2="4" y2="12"/><line x1="12" y1="4" x2="13" y2="3"/></svg>',
  dark:
    '<svg viewBox="0 0 16 16" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M13.5 9.5A5.5 5.5 0 0 1 6.5 2.5a5.5 5.5 0 0 0 7 7z"/></svg>',
};

export class KeepsApp extends HTMLElement {
  private store: (Store & DrawingStore) | undefined;
  private sync: SyncPort | undefined;
  private view: NoteView = "notes";
  private emptyArmed = false;
  private emptyTrashTimer: number | undefined;
  private theme: Theme = "dark";
  private query = "";
  private editingId: string | null = null;
  private composerOpen = false;
  private navOpen = false;
  private unsub: (() => void) | undefined;
  private devices: DevicesClient | undefined;
  private devicesOpen = false;
  private devicesCache: DeviceInfo[] | null = null;
  private toast: { message: string; undo: boolean } | null = null;
  private pendingDelete: Note | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | undefined;
  private undoMs = 6_000;
  private labels: LabelsStore | undefined;
  private labelCache: Label[] = [];
  private activeLabelId: string | null = null;
  private editingLabelId: string | null = null;
  private labelsUnsub: (() => void) | undefined;

  async connect(
    store: Store & DrawingStore,
    sync: SyncPort,
    devices?: DevicesClient,
    labels?: LabelsStore,
  ): Promise<void> {
    this.store = store;
    this.sync = sync;
    this.devices = devices ?? new DevicesClient();
    this.labels = labels ?? new LabelsStore();
    this.unsub = store.subscribe(() => {
      void this.refresh();
    });
    this.labelsUnsub = this.labels.subscribe(() => {
      void this.refresh();
    });
    this.addEventListener("card-action", (event) => {
      const { id, kind } = (event as CustomEvent).detail as {
        id: string;
        kind: CardActionKind;
      };
      void this.onCardAction(id, kind);
    });
    this.addEventListener("note-save", (event) => {
      void this.onSave((event as CustomEvent).detail as NoteDraft);
    });
    this.addEventListener("drawing-open", () => {
      (
        this.querySelector("drawing-dialog") as unknown as {
          open: () => void;
        }
      ).open();
    });
    this.addEventListener("drawing-save", (event) => {
      void this.onDrawingSave(
        (event as CustomEvent).detail as DrawingDraft,
      );
    });
    this.addEventListener("note-cancel", () => {
      this.editingId = null;
    });
    this.addEventListener("click", (event) => this.onClick(event));
    this.addEventListener("input", (event) => this.onInput(event));
    this.addEventListener("change", (event) => this.onChange(event));
    this.initTheme();
    await this.refresh();
  }

  /** Theme: stored choice wins, otherwise the OS preference (followed live). */
  private initTheme(): void {
    let stored: string | null = null;
    try {
      stored = globalThis.localStorage?.getItem("keeps-theme") ?? null;
    } catch {
      stored = null;
    }
    const media =
      typeof window !== "undefined" && typeof window.matchMedia === "function"
        ? window.matchMedia("(prefers-color-scheme: light)")
        : null;
    this.theme =
      stored === "light" || stored === "dark"
        ? stored
        : media?.matches
          ? "light"
          : "dark";
    this.applyTheme();
    if (!stored && media && typeof media.addEventListener === "function") {
      media.addEventListener("change", (event) => {
        try {
          if (globalThis.localStorage?.getItem("keeps-theme")) return;
        } catch {
          return;
        }
        this.theme = event.matches ? "light" : "dark";
        this.applyTheme();
      });
    }
  }

  private applyTheme(): void {
    this.ownerDocument.documentElement.dataset["theme"] = this.theme;
    const button = this.querySelector(".theme-toggle");
    if (button) {
      const next = this.theme === "dark" ? "light" : "dark";
      button.setAttribute(
        "aria-label",
        this.theme === "dark" ? "Switch to light theme" : "Switch to dark theme",
      );
      button.innerHTML = THEME_ICONS[next] ?? "";
    }
  }

  private toggleTheme(): void {
    this.theme = this.theme === "dark" ? "light" : "dark";
    try {
      globalThis.localStorage?.setItem("keeps-theme", this.theme);
    } catch {
      // A stored theme is a nicety; the toggle still works for the session.
    }
    buzz("tap");
    this.applyTheme();
  }

  disconnectedCallback(): void {
    this.clearToastTimer();
    this.unsub?.();
    this.labelsUnsub?.();
  }

  private requireLabels(): LabelsStore {
    if (!this.labels) throw new Error("keeps-app: connect() first");
    return this.labels;
  }

  private labelNameMap(): Record<string, string> {
    const map: Record<string, string> = {};
    for (const l of this.labelCache) map[l.id] = l.name;
    return map;
  }

  private labelColorMap(): Record<string, string> {
    const map: Record<string, string> = {};
    for (const l of this.labelCache) map[l.id] = l.color;
    return map;
  }

  private labelOptions(): LabelOption[] {
    return this.labelCache.map((l) => ({ id: l.id, name: l.name }));
  }

  private applyLabelFilter(notes: Note[]): Note[] {
    if (!this.activeLabelId) return notes;
    return notes.filter((n) => (n.labelIds ?? []).includes(this.activeLabelId as string));
  }

  setSyncStatus(status: SyncStatus): void {
    const text =
      status === "idle"
        ? ""
        : status === "syncing"
          ? "Syncing…"
          : status === "offline"
            ? "Offline — changes saved locally"
            : "Sync error — will retry";
    const el = this.querySelector(".sync-status");
    if (el) {
      el.textContent = text;
      el.classList.toggle("is-syncing", status === "syncing");
    }
  }

  private toastHtml(): string {
    if (!this.toast) return "";
    const action = this.toast.undo
      ? `<button data-toast="undo">Undo</button>`
      : "";
    return `<div class="toast" role="status"><span>${escapeHtml(this.toast.message)}</span>${action}</div>`;
  }

  private renderToast(): void {
    this.querySelector(".toast")?.remove();
    if (this.toast) this.insertAdjacentHTML("beforeend", this.toastHtml());
  }

  private clearToastTimer(): void {
    if (this.toastTimer !== undefined) {
      clearTimeout(this.toastTimer);
      this.toastTimer = undefined;
    }
  }

  private dismissToast(): void {
    this.clearToastTimer();
    this.toast = null;
    this.pendingDelete = null;
    this.querySelector(".toast")?.remove();
  }

  private showToast(message: string, deleted: Note | null): void {
    this.clearToastTimer();
    this.toast = { message, undo: deleted !== null };
    this.pendingDelete = deleted;
    this.renderToast();
    this.toastTimer = setTimeout(() => this.dismissToast(), this.undoMs);
  }

  private async onUndoDelete(): Promise<void> {
    const snapshot = this.pendingDelete;
    this.dismissToast();
    if (!snapshot) return;
    await this.requireStore().put(snapshot);
    buzz("tap");
    this.sync?.schedulePush();
  }

  private async onExport(): Promise<void> {
    const json = await this.requireStore().exportJson();
    const doc = this.ownerDocument;
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = doc.createElement("a");
    link.href = url;
    link.download = "keeps-export.json";
    doc.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  private async onImportFile(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    let text: string;
    try {
      text = await file.text();
      await this.requireStore().importJson(text);
    } catch {
      buzz("error");
      this.showToast("Import failed — invalid file", null);
      return;
    }
    buzz("confirm");
    this.sync?.schedulePush();
  }

  private requireStore(): Store & DrawingStore {
    if (!this.store) throw new Error("keeps-app: connect() first");
    return this.store;
  }

  private async refresh(): Promise<void> {
    const store = this.requireStore();
    this.labelCache = this.requireLabels().all();
    if (this.activeLabelId && !this.labelCache.some((l) => l.id === this.activeLabelId)) {
      this.activeLabelId = null;
    }
    const found = this.query
      ? await store.search(this.query, this.view)
      : await store.list(this.view);
    const notes = this.applyLabelFilter(found);
    const doc = this.ownerDocument;
    const searchHadFocus =
      doc.activeElement?.classList?.contains("search") ?? false;
    // renderShell rebuilds innerHTML, which would destroy an open editor
    // or composer draft the moment a background sync lands. Stash the
    // live field values and restore them after. (Undo history inside the
    // editor resets; the text itself survives.)
    const stash = this.stashDialogs();
    this.renderShell();
    this.renderGrid(notes);
    if (this.devicesOpen) {
      await this.paintDevices();
    }
    this.restoreDialogs(stash);
    void this.paintDrawings().catch((error) => {
      console.error("[keeps] paint drawings failed:", error);
    });
    if (searchHadFocus) {
      const input = this.querySelector<HTMLInputElement>(".search");
      input?.focus();
      input?.setSelectionRange(input.value.length, input.value.length);
    }
  }

  private stashDialogs(): {
    editor: NoteDraft | null;
    composer: { title: string; body: string } | null;
  } {
    const editor =
      this.querySelector(".editor") === null
        ? null
        : {
            title: this.querySelector<HTMLInputElement>(".editor-title")?.value ?? "",
            body: this.querySelector<HTMLTextAreaElement>(".editor-body")?.value ?? "",
            color: this.querySelector<HTMLSelectElement>(".editor-color")?.value ?? "default",
            labelIds: [...this.querySelectorAll<HTMLInputElement>(".label-check")]
              .filter((box) => box.checked)
              .map((box) => box.value),
            reminderAt: parseReminderInput(
              this.querySelector<HTMLInputElement>(".editor-reminder")?.value ?? "",
            ),
          };
    const composer = !this.composerOpen
      ? null
      : {
          title: this.querySelector<HTMLInputElement>(".composer-title")?.value ?? "",
          body: this.querySelector<HTMLTextAreaElement>(".composer-body")?.value ?? "",
        };
    return { editor, composer };
  }

  private restoreDialogs(stash: {
    editor: NoteDraft | null;
    composer: { title: string; body: string } | null;
  }): void {
    if (stash.editor) {
      (
        this.querySelector("note-editor") as unknown as {
          open: (draft: NoteDraft, labels?: LabelOption[]) => void;
        }
      ).open(stash.editor, this.labelOptions());
    }
    if (stash.composer && this.composerOpen) {
      const title = this.querySelector<HTMLInputElement>(".composer-title");
      const body = this.querySelector<HTMLTextAreaElement>(".composer-body");
      if (title) title.value = stash.composer.title;
      if (body) body.value = stash.composer.body;
    }
  }

  private renderShell(): void {
    this.innerHTML = `
      <header class="topbar">
        <button class="nav-toggle" data-nav="toggle" aria-label="Toggle navigation" aria-expanded="${this.navOpen}">${NAV_ICON}</button>
        <input class="search" placeholder="Search notes" aria-label="Search notes" value="${escapeHtml(this.query)}" />
        <button class="theme-toggle" data-theme-toggle aria-label="${this.theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}">${this.theme === "dark" ? THEME_ICONS["light"] : THEME_ICONS["dark"]}</button>
        <button class="new-note" data-new="open">+ New</button>
      </header>
      <div class="layout${this.navOpen ? " nav-open" : ""}">
        <nav class="sidebar" aria-label="Views">
          ${VIEWS.map(
            (v) =>
              `<button data-view="${v.id}"${v.id === this.view && !this.devicesOpen ? ' aria-current="page"' : ""}>${ICONS[v.id]}<span>${v.label}</span></button>`,
          ).join("")}
          <button data-view="devices"${this.devicesOpen ? ' aria-current="page"' : ""}>${DEVICE_ICON}<span>Devices</span></button>
          <div class="sidebar-labels" aria-label="Labels">
            <div class="sidebar-labels-head"><span>Labels</span></div>
            ${this.labelCache
              .map((l) =>
                this.editingLabelId === l.id
                  ? `<div class="label-row" data-label-row="${escapeHtml(l.id)}">
                       <input class="label-rename-input" value="${escapeHtml(l.name)}" maxlength="120" aria-label="Label name" />
                       <button data-label-rename-save="${escapeHtml(l.id)}">Save</button>
                       <button data-label-rename-cancel>Cancel</button>
                     </div>`
                  : `<div class="label-row" data-label-row="${escapeHtml(l.id)}">
                       <button data-label-filter="${escapeHtml(l.id)}"${l.id === this.activeLabelId ? ' aria-current="page" class="is-active"' : ""}>${escapeHtml(l.name)}</button>
                       <button data-label-rename="${escapeHtml(l.id)}" title="Rename label">Rename</button>
                       <button data-label-delete="${escapeHtml(l.id)}" title="Delete label">Delete</button>
                     </div>`,
              )
              .join("")}
            <div class="label-create-row">
              <input class="label-create-input" placeholder="New label" maxlength="120" aria-label="New label name" />
              <button data-label-create>Create</button>
            </div>
          </div>
          <div class="sidebar-footer">
            <button data-export>Export</button>
            <button data-import>Import</button>
            <input class="import-file" type="file" accept="application/json,.json" hidden />
          </div>
        </nav>
        <main class="content">
          ${
            this.devicesOpen
              ? this.devicesHtml()
              : `${this.view === "notes" ? this.composerHtml() : ""}
          <div class="sync-status" role="status"></div>
          <div class="grid"></div>`
          }
        </main>
      </div>
      ${this.toastHtml()}
      <note-editor></note-editor>
      <drawing-dialog></drawing-dialog>`;
  }

  private composerHtml(): string {
    if (!this.composerOpen) {
      return `<button class="composer-closed" data-composer="open">Take a note…</button>`;
    }
    return `
      <div class="composer">
        <input class="composer-title" placeholder="Title" />
        <textarea class="composer-body" placeholder="Take a note..."></textarea>
        <div class="composer-row">
          <span class="editor-spacer"></span>
          <button data-composer="close">Close</button>
          <button data-composer="save">Save</button>
        </div>
      </div>`;
  }

  private devicesHtml(): string {
    return `
      <div class="devices">
        <div class="devices-head">
          <h2>Devices</h2>
          <button data-device-action="refresh">Refresh</button>
        </div>
        <p class="devices-sub">Each phone gets its own login. Revoking one
        signs out that phone without affecting anything else.</p>
        <div class="device-list"></div>
        <div class="devices-status" role="status"></div>
      </div>`;
  }

  private requireDevices(): DevicesClient {
    if (!this.devices) throw new Error("keeps-app: connect() first");
    return this.devices;
  }

  private setDevicesStatus(text: string): void {
    const el = this.querySelector(".devices-status");
    if (el) el.textContent = text;
  }

  private static formatTime(ts: number): string {
    if (!Number.isFinite(ts) || ts <= 0) return "never";
    return new Date(ts).toLocaleString();
  }

  /** Render the cached device list; fetches once per open/refresh. */
  private async paintDevices(): Promise<void> {
    const list = this.querySelector(".device-list");
    if (!list) return;
    if (this.devicesCache === null) {
      try {
        this.devicesCache = await this.requireDevices().list();
      } catch {
        buzz("error");
        list.innerHTML = `<div class="empty">Couldn't load devices. Check your connection and refresh.</div>`;
        return;
      }
    }
    const doc = this.ownerDocument;
    list.textContent = "";
    if (this.devicesCache.length === 0) {
      const empty = doc.createElement("div");
      empty.className = "empty";
      empty.textContent = "No phones enrolled yet.";
      list.appendChild(empty);
      return;
    }
    for (const d of this.devicesCache) {
      const row = doc.createElement("div");
      row.className = "device-row";
      row.dataset["id"] = d.id;
      row.innerHTML = d.revoked
        ? `<span class="device-name is-revoked">${escapeHtml(d.deviceName || "Unnamed phone")}</span>
           <span class="device-meta">added ${escapeHtml(KeepsApp.formatTime(d.createdAt))}</span>
           <span class="device-badge">Revoked</span>`
        : `<input class="device-name" value="${escapeHtml(d.deviceName)}" aria-label="Device name" maxlength="120" />
           <span class="device-meta">last seen ${escapeHtml(KeepsApp.formatTime(d.lastSeenAt))}</span>
           <button data-device-action="rename">Rename</button>
           <button data-device-action="revoke">Revoke</button>`;
      list.appendChild(row);
    }
  }

  /** Two-step empty-trash: arm on first click, delete on second. */
  private async onEmptyTrash(): Promise<void> {
    if (!this.emptyArmed) {
      this.emptyArmed = true;
      buzz("tap");
      await this.refresh();
      window.clearTimeout(this.emptyTrashTimer);
      this.emptyTrashTimer = window.setTimeout(() => {
        this.emptyArmed = false;
        void this.refresh();
      }, 5000);
      return;
    }
    window.clearTimeout(this.emptyTrashTimer);
    this.emptyArmed = false;
    try {
      const trash = await this.requireStore().list("trash");
      for (const note of trash) await this.sync?.deleteForever(note.id);
      buzz("destructive");
    } catch {
      buzz("error");
      const el = this.querySelector(".sync-status");
      if (el) el.textContent = "Connect to finish emptying trash";
    }
    await this.refresh();
  }

  private async onDeviceAction(button: HTMLElement, action: string): Promise<void> {
    if (action === "refresh") {
      buzz("tap");
      this.devicesCache = null;
      await this.refresh();
      return;
    }
    const row = button.closest(".device-row") as HTMLElement | null;
    const id = row?.dataset["id"];
    if (!id) return;
    try {
      if (action === "rename") {
        const name =
          row?.querySelector<HTMLInputElement>(".device-name")?.value ?? "";
        await this.requireDevices().rename(id, name);
        buzz("confirm");
        this.setDevicesStatus("Renamed.");
      } else if (action === "revoke") {
        await this.requireDevices().revoke(id);
        buzz("destructive");
        this.setDevicesStatus("Revoked — that phone is signed out.");
      } else {
        return;
      }
    } catch {
      buzz("error");
      this.setDevicesStatus("That didn't work. Check your connection and try again.");
      return;
    }
    // refresh() rebuilds the panel, so set the confirmation after it.
    const done =
      action === "rename" ? "Renamed." : "Revoked — that phone is signed out.";
    this.devicesCache = null;
    await this.refresh();
    this.setDevicesStatus(done);
  }

  private renderGrid(notes: Note[]): void {
    const grid = this.querySelector(".grid");
    if (!grid) return;
    grid.textContent = "";
    const doc = this.ownerDocument;
    if (this.view === "trash" && notes.length > 0) {
      const head = doc.createElement("div");
      head.className = "trash-head";
      const count = doc.createElement("span");
      count.textContent = `${notes.length} item${notes.length === 1 ? "" : "s"}`;
      const empty = doc.createElement("button");
      empty.dataset["emptyTrash"] = "";
      empty.textContent = this.emptyArmed ? "Click again to confirm" : "Empty trash";
      head.append(count, empty);
      grid.appendChild(head);
    }
    const names = this.labelNameMap();
    const colors = this.labelColorMap();
    for (const note of notes) {
      const el = doc.createElement("note-card");
      (el as unknown as { labelNames: Record<string, string> }).labelNames = names;
      (el as unknown as { labelColors: Record<string, string> }).labelColors = colors;
      (el as unknown as { note: Note }).note = note;
      grid.appendChild(el);
    }
    if (notes.length === 0) {
      const empty = doc.createElement("div");
      empty.className = "empty";
      empty.textContent =
        this.view === "trash" ? "Trash is empty" : "No notes yet";
      grid.appendChild(empty);
    }
  }

  private openEditor(id: string | null, draft: NoteDraft): void {
    this.editingId = id;
    (
      this.querySelector("note-editor") as unknown as {
        open: (draft: NoteDraft, labels?: LabelOption[]) => void;
      }
    ).open(draft, this.labelOptions());
  }

  private onLabelClick(button: HTMLElement): boolean {
    const ds = (button as HTMLElement).dataset;
    if (ds["labelCreate"] !== undefined) {
      const input = this.querySelector<HTMLInputElement>(".label-create-input");
      const name = input?.value.trim() ?? "";
      if (name.length === 0) return true;
      try {
        this.requireLabels().create(name);
        buzz("confirm");
      } catch {
        buzz("error");
      }
      void this.refresh();
      return true;
    }
    const filterId = ds["labelFilter"];
    if (filterId !== undefined) {
      this.activeLabelId = filterId === this.activeLabelId ? null : filterId;
      buzz("tap");
      void this.refresh();
      return true;
    }
    const renameId = ds["labelRename"];
    if (renameId !== undefined) {
      this.editingLabelId = renameId;
      void this.refresh();
      return true;
    }
    if (ds["labelRenameCancel"] !== undefined) {
      this.editingLabelId = null;
      void this.refresh();
      return true;
    }
    const renameSaveId = ds["labelRenameSave"];
    if (renameSaveId !== undefined) {
      const input = this.querySelector<HTMLInputElement>(".label-rename-input");
      const name = input?.value.trim() ?? "";
      this.editingLabelId = null;
      if (name.length > 0) {
        try {
          this.requireLabels().rename(renameSaveId, name);
          buzz("confirm");
        } catch {
          buzz("error");
        }
      }
      void this.refresh();
      return true;
    }
    const deleteId = ds["labelDelete"];
    if (deleteId !== undefined) {
      if (this.activeLabelId === deleteId) this.activeLabelId = null;
      this.requireLabels().remove(deleteId);
      buzz("destructive");
      void this.refresh();
      return true;
    }
    return false;
  }

  private onClick(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (target.closest("[data-theme-toggle]")) {
      this.toggleTheme();
      return;
    }
    const navBtn = target.closest("[data-nav]");
    if (navBtn) {
      // Class toggle only: a full refresh would wipe an open editor,
      // the composer draft, and search focus. renderShell() still stamps
      // the persisted navOpen on full re-renders.
      this.navOpen = !this.navOpen;
      this.querySelector(".layout")?.classList.toggle("nav-open", this.navOpen);
      navBtn.setAttribute("aria-expanded", String(this.navOpen));
      return;
    }
    const newBtn = target.closest("[data-new]");
    if (newBtn) {
      this.openEditor(null, { title: "", body: "", color: "default" });
      return;
    }
    const viewBtn = target.closest("[data-view]");
    if (viewBtn) {
      const id = (viewBtn as HTMLElement).dataset["view"];
      this.editingId = null;
      this.navOpen = false;
      if (id === "devices") {
        this.devicesOpen = true;
        this.devicesCache = null;
        void this.refresh();
        return;
      }
      this.view = id as NoteView;
      this.devicesOpen = false;
      this.emptyArmed = false;
      window.clearTimeout(this.emptyTrashTimer);
      void this.refresh();
      return;
    }
    if (target.closest("[data-empty-trash]")) {
      void this.onEmptyTrash();
      return;
    }
    const toastBtn = target.closest("[data-toast]");
    if (toastBtn) {
      if ((toastBtn as HTMLElement).dataset["toast"] === "undo") {
        void this.onUndoDelete();
      } else {
        this.dismissToast();
      }
      return;
    }
    const exportBtn = target.closest("[data-export]");
    if (exportBtn) {
      void this.onExport();
      return;
    }
    const importBtn = target.closest("[data-import]");
    if (importBtn) {
      this.querySelector<HTMLInputElement>(".import-file")?.click();
      return;
    }
    const deviceBtn = target.closest("[data-device-action]");
    if (deviceBtn) {
      void this.onDeviceAction(
        deviceBtn as HTMLElement,
        (deviceBtn as HTMLElement).dataset["device-action"] ?? "",
      );
      return;
    }
    const labelBtn = target.closest(
      '[data-label-create],[data-label-filter],[data-label-rename],[data-label-rename-save],[data-label-rename-cancel],[data-label-delete]',
    );
    if (labelBtn && this.onLabelClick(labelBtn as HTMLElement)) return;
    const composerBtn = target.closest("[data-composer]");
    if (composerBtn) {
      const action = (composerBtn as HTMLElement).dataset["composer"];
      if (action === "open") {
        this.composerOpen = true;
        void this.refresh();
      } else if (action === "close") {
        this.composerOpen = false;
        void this.refresh();
      } else if (action === "save") {
        void this.saveComposer();
      }
    }
  }

  private onChange(event: Event): void {
    const target = event.target as HTMLElement;
    if (target.classList.contains("import-file")) {
      void this.onImportFile(target as HTMLInputElement);
    }
  }

  private onInput(event: Event): void {
    const target = event.target as HTMLElement;
    if (target.classList.contains("search")) {
      this.query = (target as HTMLInputElement).value;
      void this.refreshGridOnly();
    }
  }

  private async refreshGridOnly(): Promise<void> {
    const store = this.requireStore();
    const found = this.query
      ? await store.search(this.query, this.view)
      : await store.list(this.view);
    this.renderGrid(this.applyLabelFilter(found));
  }

  private async saveComposer(): Promise<void> {
    const title = this.querySelector<HTMLInputElement>(".composer-title")?.value ?? "";
    const body = this.querySelector<HTMLTextAreaElement>(".composer-body")?.value ?? "";
    if (title.trim() === "" && body.trim() === "") {
      this.composerOpen = false;
      await this.refresh();
      return;
    }
    await this.requireStore().put(
      newNote({ id: crypto.randomUUID(), title, body }),
    );
    this.composerOpen = false;
    buzz("confirm");
    this.sync?.schedulePush();
  }

  private async onCardAction(id: string, kind: CardActionKind): Promise<void> {
    const store = this.requireStore();
    const note = await store.get(id);
    if (!note) return;
    switch (kind) {
      case "open": {
        if (note.deleted) return;
        this.openEditor(id, {
          title: note.title,
          body: note.body,
          color: note.color,
          labelIds: note.labelIds,
          reminderAt: note.reminderAt,
          repeat: note.repeat,
        });
        return;
      }
      case "pin":
        await store.put({ ...note, pinned: !note.pinned, updatedAt: Date.now() });
        buzz("tap");
        break;
      case "archive":
        await store.put({ ...note, archived: !note.archived, updatedAt: Date.now() });
        buzz("tap");
        break;
      case "delete":
        await store.remove(id);
        buzz("destructive");
        this.showToast("Deleted", note);
        break;
      case "restore":
        await store.restore(id);
        buzz("tap");
        break;
      case "delete-forever": {
        try {
          await this.sync?.deleteForever(id);
          buzz("confirm");
        } catch {
          buzz("error");
          const el = this.querySelector(".sync-status");
          if (el) el.textContent = "Connect to finish deleting forever";
        }
        return;
      }
    }
    this.sync?.schedulePush();
  }

  private async onSave(draft: NoteDraft): Promise<void> {
    const store = this.requireStore();
    const extras =
      draft.labelIds !== undefined ||
      draft.reminderAt !== undefined ||
      draft.repeat !== undefined
        ? {
            ...(draft.labelIds !== undefined ? { labelIds: draft.labelIds } : {}),
            ...(draft.reminderAt !== undefined ? { reminderAt: draft.reminderAt } : {}),
            ...(draft.repeat !== undefined ? { repeat: draft.repeat } : {}),
          }
        : {};
    if (this.editingId) {
      const existing = await store.get(this.editingId);
      this.editingId = null;
      if (!existing) return;
      await store.put({ ...existing, ...draft, ...extras, updatedAt: Date.now() });
    } else {
      if (draft.title.trim() === "" && draft.body.trim() === "") return;
      await store.put(newNote({ id: crypto.randomUUID(), ...draft, ...extras }));
    }
    buzz("confirm");
    this.sync?.schedulePush();
  }

  private async onDrawingSave(draft: DrawingDraft): Promise<void> {
    const store = this.requireStore();
    const id = crypto.randomUUID();
    const drawing: Drawing = {
      id,
      strokes: draft.strokes,
      updatedAt: Date.now(),
      deleted: false,
    };
    if (draft.strokes.length === 0) return;
    await store.putDrawing(drawing);
    buzz("confirm");
    // Attach to the open editor (the Draw button only exists there).
    const body = this.querySelector<HTMLTextAreaElement>(".editor-body");
    if (body) {
      const marker = `![drawing](${id})`;
      const caret = body.selectionStart ?? body.value.length;
      const prefix = body.value.length === 0 || body.value.endsWith("\n") ? "" : "\n";
      body.value = `${body.value.slice(0, caret)}${prefix}${marker}\n${body.value.slice(caret)}`;
      body.focus();
      body.dispatchEvent(new Event("input", { bubbles: true }));
    }
    this.sync?.schedulePush();
  }

  /** Paint every drawing canvas in the grid from the local store. */
  private async paintDrawings(): Promise<void> {
    const store = this.requireStore();
    const canvases = this.querySelectorAll<HTMLCanvasElement>("canvas.drawing");
    for (const canvas of canvases) {
      const id = canvas.dataset["drawing"];
      if (!id) continue;
      const drawing = id ? await store.getDrawing(id) : undefined;
      if (!drawing || drawing.deleted) continue;
      const ctx = canvas.getContext("2d");
      if (!ctx) continue;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      for (const stroke of drawing.strokes) {
        if (stroke.points.length < 2) continue;
        ctx.strokeStyle = stroke.color;
        ctx.lineWidth = stroke.width * (canvas.width / 600);
        ctx.lineCap = "round";
        ctx.lineJoin = "round";
        ctx.beginPath();
        stroke.points.forEach((p, i) => {
          const x = p.x * canvas.width;
          const y = p.y * canvas.height;
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        });
        ctx.stroke();
      }
    }
  }
}

if (typeof customElements !== "undefined" && !customElements.get("keeps-app")) {
  customElements.define("keeps-app", KeepsApp);
}
