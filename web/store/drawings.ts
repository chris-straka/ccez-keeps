// Lane B: drawings persistence seam. Implemented by MemoryStore
// (tests/fixtures) and IdbStore (prod) alongside the notes Store so both
// backends carry drawings without drifting. Watermarks mirror the notes
// cursor/pushMark pair (independent seq cursor on the server).
import type { Drawing } from "../../shared/drawing.js";

export interface DrawingStore {
  readonly ready: Promise<void>;
  allDrawings(): Promise<Drawing[]>;
  getDrawing(id: string): Promise<Drawing | undefined>;
  putDrawing(drawing: Drawing): Promise<void>;
  getDrawingsCursor(): Promise<number>;
  setDrawingsCursor(value: number): Promise<void>;
  getDrawingsPushMark(): Promise<number>;
  setDrawingsPushMark(value: number): Promise<void>;
}
