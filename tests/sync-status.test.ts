// Lane F: boot-sync status honesty — airplane mode reads offline,
// anything else names the failure instead of crying offline.
import { describe, expect, test } from "bun:test";
import { bootFailureText } from "../web/sync-status.js";

describe("bootFailureText", () => {
  test("offline browser reads offline regardless of error", () => {
    expect(bootFailureText(new Error("pull failed: 401"), false)).toBe(
      "Offline — changes saved locally",
    );
  });

  test("HTTP failures name their status", () => {
    expect(bootFailureText(new Error("pull failed: 401"), true)).toBe(
      "Sync unavailable (HTTP 401) — retrying",
    );
    expect(bootFailureText(new Error("push failed: 503"), true)).toBe(
      "Sync unavailable (HTTP 503) — retrying",
    );
  });

  test("network failures without a status read as network", () => {
    expect(bootFailureText(new TypeError("fetch failed"), true)).toBe(
      "Sync unavailable (network) — retrying",
    );
  });
});
