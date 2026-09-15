// Lane A: freehand drawing dialog. Emits `drawing-save` with
// `{ strokes }` (normalized 0..1 coordinates, see shared/drawing.ts) or
// `drawing-cancel`. Strokes stay vector: tiny payloads, crisp at any size,
// and directly portable to the Android Canvas renderer.
import type { DrawingStroke } from "../../shared/drawing.js";

export interface DrawingDraft {
  strokes: DrawingStroke[];
}

const COLORS = ["#ffffff", "#ff5a5a", "#ffd60a", "#30d158", "#0a84ff"];
const WIDTHS = [3, 7, 14];

export class DrawingDialog extends HTMLElement {
  private strokes: DrawingStroke[] = [];
  private current: DrawingStroke | null = null;
  private color = COLORS[0] as string;
  private width = WIDTHS[1] as number;

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
        case "undo-stroke":
          this.strokes.pop();
          this.paint();
          break;
        case "clear":
          this.strokes = [];
          this.paint();
          break;
        case "color":
          this.color = action.dataset["value"] ?? this.color;
          this.paintSwatches();
          break;
        case "width":
          this.width = Number(action.dataset["value"]) || this.width;
          this.paintSwatches();
          break;
      }
    });
    this.addEventListener("keydown", (event) => {
      if (event.key === "Escape") this.close(false);
    });
  }

  open(): void {
    this.strokes = [];
    this.current = null;
    this.innerHTML = `
      <div class="editor-overlay" data-close>
        <div class="editor draw-dialog" role="dialog" aria-modal="true" aria-label="Draw">
          <canvas class="draw-canvas" width="900" height="600"></canvas>
          <div class="editor-tools" role="toolbar" aria-label="Draw tools">
            ${COLORS.map(
              (c) =>
                `<button data-action="color" data-value="${c}" aria-label="Color ${c}"><span class="swatch" style="background:${c}"></span></button>`,
            ).join("")}
            ${WIDTHS.map(
              (w) => `<button data-action="width" data-value="${w}" aria-label="Width ${w}">${w}</button>`,
            ).join("")}
            <button data-action="undo-stroke" aria-label="Undo stroke">Undo</button>
            <button data-action="clear" aria-label="Clear">Clear</button>
          </div>
          <div class="editor-row">
            <span class="editor-spacer"></span>
            <button data-action="cancel">Close</button>
            <button data-action="save">Save</button>
          </div>
        </div>
      </div>`;
    const canvas = this.querySelector<HTMLCanvasElement>(".draw-canvas");
    if (canvas) {
      canvas.addEventListener("pointerdown", (e) => this.begin(e));
      canvas.addEventListener("pointermove", (e) => this.move(e));
      canvas.addEventListener("pointerup", (e) => this.end(e));
      canvas.addEventListener("pointercancel", (e) => this.end(e));
    }
    this.paintSwatches();
  }

  private canvas(): HTMLCanvasElement | null {
    return this.querySelector<HTMLCanvasElement>(".draw-canvas");
  }

  private point(event: PointerEvent): { x: number; y: number } {
    const rect = (event.target as HTMLCanvasElement).getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)),
    };
  }

  private begin(event: PointerEvent): void {
    event.preventDefault();
    (event.target as HTMLCanvasElement).setPointerCapture?.(event.pointerId);
    this.current = { color: this.color, width: this.width, points: [this.point(event)] };
  }

  private move(event: PointerEvent): void {
    if (!this.current) return;
    event.preventDefault();
    const pts = this.current.points;
    if (pts.length < 2000) pts.push(this.point(event));
    this.paint();
  }

  private end(event: PointerEvent): void {
    if (!this.current) return;
    event.preventDefault();
    if (this.current.points.length > 1 && this.strokes.length < 200) {
      this.strokes.push(this.current);
    }
    this.current = null;
    this.paint();
  }

  private paint(): void {
    const canvas = this.canvas();
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (const stroke of [...this.strokes, ...(this.current ? [this.current] : [])]) {
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

  private paintSwatches(): void {
    for (const btn of this.querySelectorAll<HTMLButtonElement>('[data-action="color"]')) {
      btn.classList.toggle("picked", btn.dataset["value"] === this.color);
    }
    for (const btn of this.querySelectorAll<HTMLButtonElement>('[data-action="width"]')) {
      btn.classList.toggle("picked", Number(btn.dataset["value"]) === this.width);
    }
  }

  private close(save: boolean): void {
    if (save) {
      const strokes = this.strokes;
      this.dispatchEvent(
        new CustomEvent("drawing-save", {
          bubbles: true,
          composed: true,
          detail: { strokes } satisfies DrawingDraft,
        }),
      );
    } else {
      this.dispatchEvent(new CustomEvent("drawing-cancel", { bubbles: true, composed: true }));
    }
    this.innerHTML = "";
  }
}

if (typeof customElements !== "undefined" && !customElements.get("drawing-dialog")) {
  customElements.define("drawing-dialog", DrawingDialog);
}
