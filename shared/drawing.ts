// Frozen-adjacent contract (see contracts/data.md "Drawings"). Vector
// strokes with normalized 0..1 coordinates; storage, sync, and validation
// treat drawings exactly like notes (own seq cursor, own endpoints).

export interface DrawingPoint {
  x: number;
  y: number;
}

export interface DrawingStroke {
  color: string;
  width: number;
  points: DrawingPoint[];
}

export interface Drawing {
  id: string;
  strokes: DrawingStroke[];
  /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
  updatedAt: number;
  /** Soft delete. True = tombstone; tombstones sync like drawings. */
  deleted: boolean;
}

export const DRAWING_LIMITS = {
  maxStrokes: 200,
  maxPointsPerStroke: 2000,
  maxWidth: 100,
  maxColorLength: 32,
} as const;

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isPoint(value: unknown): value is DrawingPoint {
  if (typeof value !== "object" || value === null) return false;
  const p = value as Record<string, unknown>;
  return isFiniteNumber(p["x"]) && isFiniteNumber(p["y"]);
}

function isStroke(value: unknown): value is DrawingStroke {
  if (typeof value !== "object" || value === null) return false;
  const s = value as Record<string, unknown>;
  return (
    typeof s["color"] === "string" &&
    s["color"].length <= DRAWING_LIMITS.maxColorLength &&
    isFiniteNumber(s["width"]) &&
    (s["width"] as number) > 0 &&
    (s["width"] as number) <= DRAWING_LIMITS.maxWidth &&
    Array.isArray(s["points"]) &&
    (s["points"] as unknown[]).length <= DRAWING_LIMITS.maxPointsPerStroke &&
    (s["points"] as unknown[]).every(isPoint)
  );
}

export function isDrawing(value: unknown): value is Drawing {
  if (typeof value !== "object" || value === null) return false;
  const d = value as Record<string, unknown>;
  return (
    typeof d["id"] === "string" &&
    d["id"].length > 0 &&
    Array.isArray(d["strokes"]) &&
    (d["strokes"] as unknown[]).length <= DRAWING_LIMITS.maxStrokes &&
    (d["strokes"] as unknown[]).every(isStroke) &&
    isFiniteNumber(d["updatedAt"]) &&
    typeof d["deleted"] === "boolean"
  );
}

/** Ids referenced by full-line `![drawing](id)` markers in a body. */
export function drawingRefs(body: string): string[] {
  const refs: string[] = [];
  for (const line of body.split("\n")) {
    const match = /^!\[drawing\]\(([^)\s]+)\)$/.exec(line.trim());
    if (match?.[1]) refs.push(match[1]);
  }
  return refs;
}
