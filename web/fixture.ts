// Lane A: deterministic note generator for perf fixtures and manual
// seeding. Same output for the same (count, seed) — perf comparisons
// stay apples-to-apples.
import { newNote, NOTE_COLORS, type Note } from "../shared/note.js";

export function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const WORDS = [
  "groceries",
  "standup",
  "dentist",
  "visa",
  "rent",
  "plants",
  "inbox",
  "flight",
  "ideas",
  "books",
  "gifts",
  "repairs",
  "calls",
  "deadline",
];

export function makeFixture(count: number, seed = 42): Note[] {
  const rand = mulberry32(seed);
  const notes: Note[] = [];
  for (let i = 0; i < count; i += 1) {
    const title = `Note ${i} ${WORDS[Math.floor(rand() * WORDS.length)]}`;
    const body = Array.from(
      { length: 1 + Math.floor(rand() * 4) },
      () => WORDS[Math.floor(rand() * WORDS.length)],
    ).join(" ");
    notes.push(
      newNote({
        id: `fixture-${i}`,
        title,
        body,
        color:
          NOTE_COLORS[Math.floor(rand() * NOTE_COLORS.length)] ?? "default",
        pinned: rand() < 0.08,
        archived: rand() < 0.12,
        updatedAt: 1_700_000_000_000 + i,
      }),
    );
  }
  return notes;
}
