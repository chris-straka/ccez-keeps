// Device management client (plan-android.md). Same-origin calls to the
// Worker's /api/devices/* routes; the browser's Access cookie is the
// credential, so no token handling here. fetchFn is injectable for tests.
export interface DeviceInfo {
  id: string;
  deviceName: string;
  createdAt: number;
  lastSeenAt: number;
  revoked: boolean;
}

function isDeviceInfo(value: unknown): value is DeviceInfo {
  const d = (value ?? {}) as Record<string, unknown>;
  return (
    typeof d["id"] === "string" &&
    typeof d["deviceName"] === "string" &&
    typeof d["createdAt"] === "number" &&
    typeof d["lastSeenAt"] === "number" &&
    typeof d["revoked"] === "boolean"
  );
}

export class DevicesClient {
  constructor(private readonly fetchFn?: typeof fetch) {}

  private get fetchImpl(): typeof fetch {
    if (this.fetchFn) return this.fetchFn;
    const fn = globalThis.fetch;
    if (!fn) throw new Error("DevicesClient: no fetch available");
    return fn.bind(globalThis);
  }

  private async post(path: string, body: unknown): Promise<unknown> {
    const res = await this.fetchImpl(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      throw new Error(`devices ${res.status}`);
    }
    return res.json() as Promise<unknown>;
  }

  async list(): Promise<DeviceInfo[]> {
    const res = await this.fetchImpl("/api/devices");
    if (!res.ok) {
      throw new Error(`devices ${res.status}`);
    }
    const body = (await res.json()) as { devices?: unknown };
    if (!Array.isArray(body.devices) || !body.devices.every(isDeviceInfo)) {
      throw new Error("devices: bad list shape");
    }
    return body.devices;
  }

  async rename(deviceId: string, deviceName: string): Promise<void> {
    await this.post("/api/devices/rename", { deviceId, deviceName });
  }

  async revoke(deviceId: string): Promise<void> {
    await this.post("/api/devices/revoke", { deviceId });
  }
}
