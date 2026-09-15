// Markdown subset renderer for note bodies. The SYNTAX is part of the
// frozen-adjacent contract (see contracts/data.md "Markdown in body"):
// **bold**, *italic*, ~~strike~~, `code`, and "- [ ] "/"- [x] " checklist
// lines. Bodies are stored verbatim; this only affects display.
//
// Security: escapeHtml runs FIRST on the raw body, so formatting markers
// can never smuggle markup — every tag below is emitted by us.
import { escapeHtml } from "./html.js";

function inlineForms(src: string): string {
  return src
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*\w])\*([^*\n]+)\*/g, "$1<em>$2</em>")
    .replace(/~~([^~]+)~~/g, "<del>$1</del>");
}

/** Inline forms, except inside `code` spans where markers stay literal. */
function inline(src: string): string {
  const parts = src.split("`");
  return parts
    .map((part, i) => (i % 2 === 1 ? `<code>${part}</code>` : inlineForms(part)))
    .join("");
}

/** One body line: checklist markers become glyphs, drawing markers become
 *  canvases (painted by keeps-app after render), everything else formats. */
function line(src: string): string {
  const trimmed = src.trim();
  const draw = /^!\[drawing\]\(([^)\s]+)\)$/.exec(trimmed);
  if (draw?.[1]) {
    return `<canvas class="drawing" data-drawing="${escapeHtml(draw[1])}" width="600" height="400"></canvas>`;
  }
  const match = /^(- \[( |x)\] )(.*)$/.exec(src);
  if (!match) return inline(src);
  const checked = match[2] === "x";
  return `<span class="check${checked ? " done" : ""}">${checked ? "☑" : "☐"}</span> ${inline(match[3] ?? "")}`;
}

/** Safe HTML for a note body. Never throws on any string input. */
export function renderBody(body: string): string {
  return escapeHtml(body).split("\n").map(line).join("\n");
}
