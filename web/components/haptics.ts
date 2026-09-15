// Touch feedback for the SPA. Vibration only exists on some mobile
// browsers, so every call is guarded: missing API, denied permission, or
// desktop simply means silence. Never throws; never blocks the action.
export type BuzzKind = "tap" | "confirm" | "destructive" | "error";

const PATTERNS: Record<BuzzKind, number | number[]> = {
  // Light tick for toggles and toolbar taps.
  tap: 10,
  // Short double-tick for completed saves.
  confirm: [12, 30, 12],
  // Heavier double-buzz for trash/delete.
  destructive: [20, 40, 20],
  // Triple-buzz for failures.
  error: [40, 40, 40],
};

/** Fire-and-forget vibration for a completed UI action. */
export function buzz(kind: BuzzKind): void {
  try {
    const nav = (globalThis as unknown as { navigator?: Navigator }).navigator;
    const vibrate = nav?.vibrate?.bind(nav);
    if (typeof vibrate !== "function") return;
    vibrate(PATTERNS[kind]);
  } catch {
    // Haptics must never break the action they accompany.
  }
}
