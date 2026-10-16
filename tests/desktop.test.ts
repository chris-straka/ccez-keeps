// Mac app bridge (web/desktop.ts): /api/* goes through the app's
// api_fetch command, everything else to the real fetch; the sync engine
// sees ordinary Responses. A fake window.__TAURI__ stands in for Tauri.
import { afterEach, describe, expect, test } from "bun:test";
import {
  apiPath,
  desktopFetch,
  desktopSignIn,
  installDesktop,
  isDesktop,
} from "../web/desktop.js";
import { MemoryStore } from "../web/store/memory-store.js";
import { SyncEngine } from "../web/store/sync.js";
import type { SyncStatus } from "../web/store/types.js";

interface Call {
  cmd: string;
  args?: Record<string, unknown>;
}

function fakeInvoke(reply: (call: Call) => unknown) {
  const calls: Call[] = [];
  const invoke = async <T>(cmd: string, args?: Record<string, unknown>): Promise<T> => {
    const call: Call = args === undefined ? { cmd } : { cmd, args };
    calls.push(call);
    return reply(call) as T;
  };
  return { calls, invoke };
}

const g = globalThis as unknown as Record<string, unknown>;
const realFetch = globalThis.fetch;

afterEach(() => {
  delete g["__TAURI__"];
  globalThis.fetch = realFetch;
});

describe("apiPath", () => {
  test("relative API paths pass through with their query", () => {
    expect(apiPath("/api/notes?since=0")).toBe("/api/notes?since=0");
    expect(apiPath("/api/devices/revoke")).toBe("/api/devices/revoke");
  });

  test("other paths and hosts are not the API", () => {
    expect(apiPath("/sw.js")).toBeNull();
    expect(apiPath("https://example.com/api/notes")).toBeNull();
    expect(apiPath(new URL("https://keeps.ccez.uk/api/notes"))).toBeNull();
  });
});

describe("desktopFetch", () => {
  test("API calls become api_fetch with method, path and body", async () => {
    const { calls, invoke } = fakeInvoke(() => ({
      status: 200,
      body: '{"ok":true}',
      contentType: "application/json",
    }));
    const fetchFn = desktopFetch(invoke, (() => {
      throw new Error("fallback must not run");
    }) as unknown as typeof fetch);
    const res = await fetchFn("/api/notes/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: '{"upserts":[]}',
    });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(calls).toEqual([
      { cmd: "api_fetch", args: { method: "POST", path: "/api/notes/sync", body: '{"upserts":[]}' } },
    ]);
  });

  test("GET has no body; statuses and empty replies survive", async () => {
    const { calls, invoke } = fakeInvoke(() => ({ status: 204, body: "", contentType: "text/plain" }));
    const res = await desktopFetch(invoke, realFetch)("/api/devices");
    expect(res.status).toBe(204);
    expect(calls[0]?.args).toEqual({ method: "GET", path: "/api/devices", body: undefined });
  });

  test("no answer at all throws fetch's TypeError", async () => {
    const { invoke } = fakeInvoke(() => {
      throw "network: dns error";
    });
    await expect(desktopFetch(invoke, realFetch)("/api/notes?since=0")).rejects.toBeInstanceOf(
      TypeError,
    );
  });

  test("non-API requests use the real fetch", async () => {
    const { calls, invoke } = fakeInvoke(() => null);
    let fellBack = "";
    const fetchFn = desktopFetch(invoke, (async (input: RequestInfo | URL) => {
      fellBack = String(input);
      return new Response("x");
    }) as unknown as typeof fetch);
    await fetchFn("/icon.svg");
    expect(fellBack).toBe("/icon.svg");
    expect(calls).toHaveLength(0);
  });
});

describe("installDesktop", () => {
  test("does nothing in a browser", () => {
    expect(isDesktop()).toBe(false);
    expect(installDesktop()).toBe(false);
    expect(desktopSignIn()).toBe(false);
    expect(globalThis.fetch).toBe(realFetch);
  });

  test("in the app, a signed-out sync shows the sign-in banner state", async () => {
    const { calls, invoke } = fakeInvoke((call) =>
      call.cmd === "api_fetch"
        ? { status: 401, body: '{"error":"not signed in"}', contentType: "application/json" }
        : undefined,
    );
    g["__TAURI__"] = { core: { invoke }, event: { listen: async () => () => {} } };
    expect(installDesktop()).toBe(true);
    const statuses: SyncStatus[] = [];
    const engine = new SyncEngine(new MemoryStore(), {
      onStatus: (s) => statuses.push(s),
      online: () => true,
      // One round: no retry timer left behind.
      retryMaxAttempts: 1,
    });
    await engine.flush();
    expect(statuses).toContain("auth");
    expect(calls.some((c) => c.cmd === "api_fetch")).toBe(true);

    expect(desktopSignIn()).toBe(true);
    expect(calls.at(-1)).toEqual({ cmd: "sign_in" });
  });
});
