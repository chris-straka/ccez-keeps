// Lane B: in-memory Store for tests, fixtures, and lane A development.
// Same BaseStore logic as prod; only persistence is a Map.
import type { Note } from "../../shared/note.js";
import type { Drawing } from "../../shared/drawing.js";
import { isDrawing } from "../../shared/drawing.js";
import { BaseStore } from "./base.js";
import type { DrawingStore } from "./drawings.js";

export class MemoryStore extends BaseStore implements DrawingStore {
  private rows = new Map<string, Note>();
  private cursor = 0;
  private pushMark = 0;
  private drawingRows = new Map<string, Drawing>();
  private drawingsCursor = 0;
  private drawingsPushMark = 0;

  constructor(seed: Note[] = []) {
    super();
    for (const note of seed) this.rows.set(note.id, note);
  }

  protected init(): Promise<void> {
    return Promise.resolve();
  }

  protected loadAll(): Promise<Note[]> {
    return Promise.resolve([...this.rows.values()]);
  }

  protected saveRaw(note: Note): Promise<void> {
    this.rows.set(note.id, note);
    return Promise.resolve();
  }

  protected dropRaw(id: string): Promise<void> {
    this.rows.delete(id);
    return Promise.resolve();
  }

  getCursor(): Promise<number> {
    return Promise.resolve(this.cursor);
  }

  setCursor(value: number): Promise<void> {
    this.cursor = value;
    return Promise.resolve();
  }

  getPushMark(): Promise<number> {
    return Promise.resolve(this.pushMark);
  }

  setPushMark(value: number): Promise<void> {
    this.pushMark = value;
    return Promise.resolve();
  }

  async allDrawings(): Promise<Drawing[]> {
    return [...this.drawingRows.values()];
  }

  async getDrawing(id: string): Promise<Drawing | undefined> {
    return this.drawingRows.get(id);
  }

  async putDrawing(drawing: Drawing): Promise<void> {
    if (!isDrawing(drawing)) throw new Error("putDrawing: not a Drawing");
    this.drawingRows.set(drawing.id, drawing);
    this.notify();
  }

  async getDrawingsCursor(): Promise<number> {
    return this.drawingsCursor;
  }

  async setDrawingsCursor(value: number): Promise<void> {
    this.drawingsCursor = value;
  }

  async getDrawingsPushMark(): Promise<number> {
    return this.drawingsPushMark;
  }

  async setDrawingsPushMark(value: number): Promise<void> {
    this.drawingsPushMark = value;
  }
}
