// Haptics helper: guarded vibration patterns that never break the UI.
import { afterEach, describe, expect, test } from "bun:test";
import { buzz } from "../web/components/haptics.js";

const globals = globalThis as unknown as Record<string, unknown>;

function setNavigator(nav: unknown): void {
  globals["navigator"] = nav;
}

afterEach(() => {
  delete globals["navigator"];
});

describe("buzz", () => {
  test("sends the tap pattern for light feedback", () => {
    const calls: unknown[] = [];
    setNavigator({ vibrate: (p: unknown) => (calls.push(p), true) });
    buzz("tap");
    expect(calls).toEqual([10]);
  });

  test("escalates destructive and error patterns", () => {
    const calls: unknown[] = [];
    setNavigator({ vibrate: (p: unknown) => (calls.push(p), true) });
    buzz("destructive");
    buzz("error");
    expect(calls).toEqual([
      [20, 40, 20],
      [40, 40, 40],
    ]);
  });

  test("is silent when vibration is unavailable, and never throws", () => {
    setNavigator(undefined);
    expect(() => buzz("confirm")).not.toThrow();
    setNavigator({});
    expect(() => buzz("tap")).not.toThrow();
    setNavigator({
      vibrate: () => {
        throw new Error("denied");
      },
    });
    expect(() => buzz("destructive")).not.toThrow();
  });
});
