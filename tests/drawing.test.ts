// Shared drawing contract: validation caps, marker refs, LWW merge.
import { describe, expect, test } from "bun:test";
import {
  drawingRefs,
  isDrawing,
  type Drawing,
} from "../shared/drawing.js";
import {
  mergeDrawingLists,
  pickDrawingWinner,
} from "../shared/drawing-sync.js";

const drawing = (overrides: Partial<Drawing> & { id: string }): Drawing => ({
  strokes: [],
  updatedAt: 1000,
  deleted: false,
  ...overrides,
});

const stroke = (points = [{ x: 0, y: 0 }, { x: 1, y: 1 }]) => ({
  color: "white",
  width: 4,
  points,
});

describe("isDrawing", () => {
  test("accepts a well-formed drawing", () => {
    expect(isDrawing(drawing({ id: "a", strokes: [stroke()] }))).toBe(true);
    expect(isDrawing(drawing({ id: "a", strokes: [], deleted: true }))).toBe(true);
  });

  test("rejects bad shapes and over-cap payloads", () => {
    expect(isDrawing(null)).toBe(false);
    expect(isDrawing({ ...drawing({ id: "a" }), id: "" })).toBe(false);
    expect(isDrawing({ ...drawing({ id: "a" }), strokes: "nope" })).toBe(false);
    expect(
      isDrawing(drawing({ id: "a", strokes: [{ ...stroke(), width: 0 }] })),
    ).toBe(false);
    expect(
      isDrawing(drawing({ id: "a", strokes: [{ ...stroke(), width: 101 }] })),
    ).toBe(false);
    expect(
      isDrawing(drawing({ id: "a", strokes: [{ ...stroke(), color: "x".repeat(33) }] })),
    ).toBe(false);
    expect(
      isDrawing(
        drawing({ id: "a", strokes: [{ ...stroke(), points: [{ x: NaN, y: 0 }] }] }),
      ),
    ).toBe(false);
    const tooMany = Array.from({ length: 201 }, () => stroke([]));
    expect(isDrawing(drawing({ id: "a", strokes: tooMany }))).toBe(false);
  });
});

describe("drawingRefs", () => {
  test("collects full-line markers only", () => {
    expect(drawingRefs("hello\n![drawing](abc)\nworld")).toEqual(["abc"]);
    expect(drawingRefs("see ![drawing](abc) here")).toEqual([]);
    expect(drawingRefs("![drawing]()")).toEqual([]);
    expect(drawingRefs("")).toEqual([]);
  });
});

describe("drawing LWW", () => {
  test("newer updatedAt wins either order; tombstones are just writes", () => {
    const old = drawing({ id: "a", updatedAt: 100 });
    const young = drawing({ id: "a", updatedAt: 200, deleted: true });
    expect(pickDrawingWinner(old, young).deleted).toBe(true);
    expect(pickDrawingWinner(young, old).deleted).toBe(true);
    expect(mergeDrawingLists([old], [young])).toHaveLength(1);
  });

  test("equal timestamps break ties deterministically", () => {
    const x = drawing({ id: "a", strokes: [stroke([{ x: 0, y: 0 }])], updatedAt: 5 });
    const y = drawing({ id: "a", strokes: [stroke([{ x: 1, y: 1 }])], updatedAt: 5 });
    expect(pickDrawingWinner(x, y)).toEqual(pickDrawingWinner(y, x));
  });
});
