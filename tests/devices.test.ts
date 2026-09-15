// DevicesClient: API shapes and request bodies, against a stub fetch
// (no network, no DOM). Panel rendering lives in components.test.ts,
// the single happy-dom owner for shell tests.
import { describe, expect, test } from "bun:test";
import { DevicesClient } from "../web/components/devices.js";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function stubFetch(handler: (url: string, init?: unknown) => Promise<Response>): typeof fetch {
  return handler as unknown as typeof fetch;
}

describe("DevicesClient", () => {
  test("list returns typed devices", async () => {
    const client = new DevicesClient(
      stubFetch(async () =>
        json(200, {
          devices: [
            { id: "a", deviceName: "pixel", createdAt: 1, lastSeenAt: 2, revoked: false },
          ],
        }),
      ),
    );
    const devices = await client.list();
    expect(devices).toHaveLength(1);
    expect(devices[0]?.deviceName).toBe("pixel");
  });

  test("HTTP errors throw with the status", async () => {
    const client = new DevicesClient(stubFetch(async () => json(401, {})));
    await expect(client.list()).rejects.toThrow("devices 401");
    await expect(client.revoke("x")).rejects.toThrow("devices 401");
  });

  test("bad list shapes throw", async () => {
    const client = new DevicesClient(
      stubFetch(async () => json(200, { devices: [{ id: "a" }] })),
    );
    await expect(client.list()).rejects.toThrow("bad list shape");
  });

  test("rename/revoke post the frozen bodies", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const client = new DevicesClient(
      stubFetch(async (url: string, init?: unknown) => {
        calls.push({
          url,
          body: JSON.parse((init as { body: string }).body),
        });
        return json(200, {});
      }),
    );
    await client.rename("d1", "pixel-9");
    await client.revoke("d1");
    expect(calls).toEqual([
      { url: "/api/devices/rename", body: { deviceId: "d1", deviceName: "pixel-9" } },
      { url: "/api/devices/revoke", body: { deviceId: "d1" } },
    ]);
  });
});
