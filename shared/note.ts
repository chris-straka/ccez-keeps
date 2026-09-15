// Frozen contract (Phase 0). Lane agents: do NOT change this file's shapes
// without a plan update. All lanes import these types.

export interface Note {
  id: string;
  title: string;
  body: string;
  /** Color key, e.g. "default" | "red" | "green" | "blue" | "yellow". */
  color: string;
  pinned: boolean;
  archived: boolean;
  /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
  updatedAt: number;
  /** Soft delete. True = tombstone; tombstones sync like notes. */
  deleted: boolean;
  /** Label ids attached to this note (max 20, each max 64 chars). */
  labelIds: string[];
  /** Reminder fire time, unix epoch ms; null = no reminder. */
  reminderAt: number | null;
  /** Repeat rule for the reminder; null = fires once. */
  repeat: "daily" | "weekly" | null;
  /**
   * Checklist items; null = plain text note. Rides note sync free under
   * LWW like labelIds (concurrent toggles from two devices: last write
   * wins the whole list). Missing (old rows/clients) means null.
   */
  checklist: ChecklistItem[] | null;
  /**
   * Image attachments as data URLs (client-downscaled). Missing
   * (old rows/clients) means none. Validated, never interpreted
   * server-side; previews use thumbUrl, full view uses dataUrl.
   */
  attachments: Attachment[];
}

/** One row of a checklist note. Client-generated id, never reused. */
export interface ChecklistItem {
  id: string;
  text: string;
  checked: boolean;
}

/** One client-downscaled image attached to a note. */
export interface Attachment {
  id: string;
  /** Original file name (display + search only, never a path). */
  name: string;
  /** Must be image/* (e.g. "image/jpeg"). */
  mime: string;
  /** Byte size of the decoded dataUrl payload. */
  size: number;
  /** Full image, `data:image/...;base64,...` within limits. */
  dataUrl: string;
  /** Small preview of the same image, same shape, tighter limit. */
  thumbUrl: string;
}

export const NOTE_LIMITS = {
  maxLabels: 20,
  maxLabelIdLength: 64,
  maxChecklistItems: 100,
  maxChecklistText: 500,
  maxChecklistIdLength: 64,
  maxAttachments: 10,
  maxAttachmentName: 200,
  /** Decoded-payload cap per full image, in bytes. */
  maxAttachmentBytes: 700_000,
  /** Decoded-payload cap per thumbnail, in bytes. */
  maxThumbBytes: 40_000,
} as const;

/** `data:image/<sub>;base64,<payload>` with a strict base64 payload. */
function isImageDataUrl(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const m = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)$/.exec(value);
  return m !== null && m[2]!.length % 4 === 0;
}

export function isChecklistItem(value: unknown): value is ChecklistItem {
  if (typeof value !== "object" || value === null) return false;
  const item = value as Record<string, unknown>;
  return (
    typeof item["id"] === "string" &&
    (item["id"] as string).length > 0 &&
    (item["id"] as string).length <= NOTE_LIMITS.maxChecklistIdLength &&
    typeof item["text"] === "string" &&
    (item["text"] as string).length <= NOTE_LIMITS.maxChecklistText &&
    typeof item["checked"] === "boolean"
  );
}

export function isAttachment(value: unknown): value is Attachment {
  if (typeof value !== "object" || value === null) return false;
  const a = value as Record<string, unknown>;
  if (
    typeof a["id"] !== "string" ||
    (a["id"] as string).length === 0 ||
    typeof a["name"] !== "string" ||
    (a["name"] as string).length === 0 ||
    (a["name"] as string).length > NOTE_LIMITS.maxAttachmentName ||
    typeof a["mime"] !== "string" ||
    !(a["mime"] as string).startsWith("image/") ||
    typeof a["size"] !== "number" ||
    !Number.isFinite(a["size"] as number) ||
    (a["size"] as number) < 0 ||
    (a["size"] as number) > NOTE_LIMITS.maxAttachmentBytes
  ) {
    return false;
  }
  if (!isImageDataUrl(a["dataUrl"])) return false;
  if (!isImageDataUrl(a["thumbUrl"])) return false;
  // Base64 inflates by 4/3, so length * 3/4 bounds the decoded bytes.
  const fullBytes = Math.ceil(((a["dataUrl"] as string).length * 3) / 4);
  const thumbBytes = Math.ceil(((a["thumbUrl"] as string).length * 3) / 4);
  if (fullBytes > NOTE_LIMITS.maxAttachmentBytes) return false;
  if (thumbBytes > NOTE_LIMITS.maxThumbBytes) return false;
  return true;
}

export const NOTE_COLORS = [
  "default",
  "red",
  "orange",
  "yellow",
  "green",
  "teal",
  "blue",
  "purple",
  "pink",
] as const;

export type NoteColor = (typeof NOTE_COLORS)[number];

export function isNote(value: unknown): value is Note {
  if (typeof value !== "object" || value === null) return false;
  const n = value as Record<string, unknown>;
  return (
    typeof n["id"] === "string" &&
    n["id"].length > 0 &&
    typeof n["title"] === "string" &&
    typeof n["body"] === "string" &&
    typeof n["color"] === "string" &&
    typeof n["pinned"] === "boolean" &&
    typeof n["archived"] === "boolean" &&
    typeof n["updatedAt"] === "number" &&
    Number.isFinite(n["updatedAt"]) &&
    typeof n["deleted"] === "boolean" &&
    Array.isArray(n["labelIds"]) &&
    (n["labelIds"] as unknown[]).length <= NOTE_LIMITS.maxLabels &&
    (n["labelIds"] as unknown[]).every(
      (id) => typeof id === "string" && id.length <= NOTE_LIMITS.maxLabelIdLength,
    ) &&
    (n["reminderAt"] === null ||
      (typeof n["reminderAt"] === "number" &&
        Number.isFinite(n["reminderAt"]) &&
        (n["reminderAt"] as number) >= 0)) &&
    (n["repeat"] === null ||
      // Released clients predate this field; missing means fires once.
      n["repeat"] === undefined ||
      n["repeat"] === "daily" ||
      n["repeat"] === "weekly") &&
    // Released clients/rows predate these fields; missing means text note.
    (n["checklist"] === null ||
      n["checklist"] === undefined ||
      (Array.isArray(n["checklist"]) &&
        (n["checklist"] as unknown[]).length <= NOTE_LIMITS.maxChecklistItems &&
        (n["checklist"] as unknown[]).every(isChecklistItem))) &&
    (n["attachments"] === undefined ||
      (Array.isArray(n["attachments"]) &&
        (n["attachments"] as unknown[]).length <= NOTE_LIMITS.maxAttachments &&
        (n["attachments"] as unknown[]).every(isAttachment)))
  );
}

/** Client-side factory. Caller owns id generation (crypto.randomUUID). */
export function newNote(
  init: Pick<Note, "id"> & Partial<Omit<Note, "id" | "updatedAt">> & { updatedAt?: number },
): Note {
  return {
    title: "",
    body: "",
    color: "default",
    pinned: false,
    archived: false,
    deleted: false,
    labelIds: [],
    reminderAt: null,
    repeat: null,
    checklist: null,
    attachments: [],
    ...init,
    updatedAt: init.updatedAt ?? Date.now(),
  };
}
