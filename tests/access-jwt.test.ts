// Lane C regression suite: Access JWT verification (worker/auth.ts).
// Keypair is generated in-test; no network, no fixtures.
import { describe, expect, test } from "bun:test";
import {
  base64UrlEncode,
  checkAccess,
  verifyAccessJwt,
} from "../worker/auth.js";

const AUD = "test-audience";
const NOW = 1_700_000_000;

async function makeKey() {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const jwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  return { privateKey: pair.privateKey, jwk: { ...jwk, kid: "k1" } };
}

async function signJwt(
  privateKey: CryptoKey,
  header: object,
  payload: object,
): Promise<string> {
  const enc = (value: object) =>
    base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
  const h = enc(header);
  const p = enc(payload);
  const sig = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    privateKey,
    new TextEncoder().encode(`${h}.${p}`),
  );
  return `${h}.${p}.${base64UrlEncode(new Uint8Array(sig))}`;
}

const payload = (overrides = {}) => ({
  aud: AUD,
  exp: NOW + 3600,
  email: "user@example.com",
  ...overrides,
});

describe("verifyAccessJwt", () => {
  test("accepts a well-formed token", async () => {
    const { privateKey, jwk } = await makeKey();
    const token = await signJwt(
      privateKey,
      { alg: "RS256", kid: "k1" },
      payload(),
    );
    const result = await verifyAccessJwt(token, {
      certs: new Map([["k1", jwk]]),
      aud: AUD,
      nowSec: NOW,
    });
    expect(result).toEqual({ ok: true, email: "user@example.com" });
  });

  test("rejects tampered payload, expiry, audience, key, shape", async () => {
    const { privateKey, jwk } = await makeKey();
    const certs = new Map([["k1", jwk]]);
    const good = await signJwt(privateKey, { alg: "RS256", kid: "k1" }, payload());

    const flip = (t: string) => `${t.slice(0, 20)}${t[20] === "A" ? "B" : "A"}${t.slice(21)}`;
    expect((await verifyAccessJwt(flip(good), { certs, aud: AUD, nowSec: NOW })).ok).toBe(false);

    const expired = await signJwt(
      privateKey,
      { alg: "RS256", kid: "k1" },
      payload({ exp: NOW - 1 }),
    );
    expect(
      await verifyAccessJwt(expired, { certs, aud: AUD, nowSec: NOW }),
    ).toEqual({ ok: false, message: "expired" });

    const wrongAud = await signJwt(
      privateKey,
      { alg: "RS256", kid: "k1" },
      payload({ aud: "other" }),
    );
    expect(
      await verifyAccessJwt(wrongAud, { certs, aud: AUD, nowSec: NOW }),
    ).toEqual({ ok: false, message: "wrong audience" });

    // Access mints `aud` as an array: accept when it contains the app AUD.
    const arrayAud = await signJwt(
      privateKey,
      { alg: "RS256", kid: "k1" },
      payload({ aud: ["other-app", AUD] }),
    );
    expect(await verifyAccessJwt(arrayAud, { certs, aud: AUD, nowSec: NOW })).toEqual({
      ok: true,
      email: "user@example.com",
    });

    // ...but an array without the app AUD is another app's token: reject.
    const foreignAud = await signJwt(
      privateKey,
      { alg: "RS256", kid: "k1" },
      payload({ aud: ["other-app"] }),
    );
    expect(
      await verifyAccessJwt(foreignAud, { certs, aud: AUD, nowSec: NOW }),
    ).toEqual({ ok: false, message: "wrong audience" });

    expect(
      await verifyAccessJwt(good, {
        certs: new Map(),
        aud: AUD,
        nowSec: NOW,
      }),
    ).toEqual({ ok: false, message: "unknown key id" });

    expect(await verifyAccessJwt("a.b", { certs, aud: AUD, nowSec: NOW })).toEqual({
      ok: false,
      message: "malformed JWT",
    });

    const hs = await signJwt(privateKey, { alg: "HS256", kid: "k1" }, payload());
    expect((await verifyAccessJwt(hs, { certs, aud: AUD, nowSec: NOW })).ok).toBe(false);
  });
});

describe("checkAccess", () => {
  test("dev bypass when env unset", async () => {
    const result = await checkAccess(new Request("http://localhost/api/notes"), {});
    expect(result.ok).toBe(true);
  });

  test("401 when token missing and env set", async () => {
    const result = await checkAccess(new Request("http://localhost/api/notes"), {
      teamDomain: "team.example.com",
      aud: AUD,
    });
    expect(result).toEqual({ ok: false, status: 401, message: "missing Access JWT" });
  });

  test("accepts valid token via injected certs (no network)", async () => {
    const { privateKey, jwk } = await makeKey();
    const token = await signJwt(privateKey, { alg: "RS256", kid: "k1" }, payload());
    const req = new Request("http://localhost/api/notes", {
      headers: { "Cf-Access-JWT-Assertion": token },
    });
    const result = await checkAccess(
      req,
      { teamDomain: "team.example.com", aud: AUD },
      { fetchCerts: async () => new Map([["k1", jwk]]), nowSec: NOW },
    );
    expect(result).toEqual({ ok: true, email: "user@example.com", devBypass: false });
  });

  test("accepts valid token via CF_Authorization cookie (no network)", async () => {
    const { privateKey, jwk } = await makeKey();
    const token = await signJwt(privateKey, { alg: "RS256", kid: "k1" }, payload());
    const req = new Request("http://localhost/api/notes", {
      headers: { Cookie: `other=1; CF_Authorization=${token}` },
    });
    const result = await checkAccess(
      req,
      { teamDomain: "team.example.com", aud: AUD },
      { fetchCerts: async () => new Map([["k1", jwk]]), nowSec: NOW },
    );
    expect(result).toEqual({ ok: true, email: "user@example.com", devBypass: false });
  });

  test("rejects a tampered cookie token", async () => {
    const { privateKey, jwk } = await makeKey();
    const token = await signJwt(privateKey, { alg: "RS256", kid: "k1" }, payload());
    const req = new Request("http://localhost/api/notes", {
      headers: { Cookie: `CF_Authorization=${token}tampered` },
    });
    const result = await checkAccess(
      req,
      { teamDomain: "team.example.com", aud: AUD },
      { fetchCerts: async () => new Map([["k1", jwk]]), nowSec: NOW },
    );
    expect(result.ok).toBe(false);
  });
});
