// Lane C: Cloudflare Access identity check for /api/* routes.
//
// Prod (agent D sets env): ACCESS_TEAM_DOMAIN + ACCESS_AUD are set, and
// every API request must carry a valid Access JWT, verified RS256 against
// the team's certs. The token comes from the Cf-Access-JWT-Assertion
// header Access appends — or, when a proxy/policy strips that header,
// from the CF_Authorization session cookie, which carries the same JWT.
// Fail-closed: 401 missing/unverifiable, 403 valid signature but wrong
// audience.
// Local dev: env unset -> allow with a warning (no edge in `wrangler dev`).

export interface AccessEnv {
  teamDomain?: string | undefined;
  aud?: string | undefined;
}

export type AccessResult =
  | { ok: true; email: string; devBypass: boolean }
  | { ok: false; status: 401 | 403; message: string };

function base64UrlDecode(input: string): Uint8Array<ArrayBuffer> {
  const padded = input.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(padded);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

interface AccessClaims {
  aud?: unknown;
  exp?: unknown;
  email?: unknown;
}

export async function verifyAccessJwt(
  token: string,
  opts: { certs: Map<string, JsonWebKey>; aud: string; nowSec: number },
): Promise<{ ok: true; email: string } | { ok: false; message: string }> {
  const parts = token.split(".");
  if (parts.length !== 3) return { ok: false, message: "malformed JWT" };
  const [headerB64, payloadB64, sigB64] = parts as [string, string, string];
  let header: { kid?: unknown; alg?: unknown };
  let claims: AccessClaims;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(headerB64)));
    claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(payloadB64)));
  } catch {
    return { ok: false, message: "undecodable JWT" };
  }
  if (header["alg"] !== "RS256" || typeof header["kid"] !== "string") {
    return { ok: false, message: "unexpected JWT alg/kid" };
  }
  const jwk = opts.certs.get(header["kid"]);
  if (!jwk) return { ok: false, message: "unknown key id" };
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
  } catch {
    return { ok: false, message: "unusable key" };
  }
  const data = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  const valid = await crypto.subtle.verify(
    { name: "RSASSA-PKCS1-v1_5" },
    key,
    base64UrlDecode(sigB64),
    data,
  );
  if (!valid) return { ok: false, message: "bad signature" };
  if (typeof claims.exp !== "number" || claims.exp <= opts.nowSec) {
    return { ok: false, message: "expired" };
  }
  // Access mints `aud` as an array of audience tags (RFC 7519 allows a
  // string or an array of strings). Accept either shape, but the expected
  // application AUD must always be present — a token for another app in
  // the same account shares the team certs and must not pass.
  const audOk = Array.isArray(claims.aud)
    ? claims.aud.includes(opts.aud)
    : claims.aud === opts.aud;
  if (!audOk) {
    return { ok: false, message: "wrong audience" };
  }
  if (typeof claims.email !== "string" || claims.email.length === 0) {
    return { ok: false, message: "missing email claim" };
  }
  return { ok: true, email: claims.email };
}

interface CertCache {
  fetchedAt: number;
  certs: Map<string, JsonWebKey>;
}

const certCache = new Map<string, CertCache>();
const CERT_TTL_MS = 6 * 60 * 60 * 1000;

export async function fetchTeamCerts(
  teamDomain: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Map<string, JsonWebKey>> {
  const cached = certCache.get(teamDomain);
  if (cached && Date.now() - cached.fetchedAt < CERT_TTL_MS) return cached.certs;
  const res = await fetchImpl(`https://${teamDomain}/cdn-cgi/access/certs`);
  if (!res.ok) throw new Error(`certs fetch failed: ${res.status}`);
  const body = (await res.json()) as { keys?: JsonWebKey[] };
  const certs = new Map(
    (body.keys ?? []).map((k) => {
      const kid = (k as { kid?: unknown }).kid;
      return [typeof kid === "string" ? kid : "", k] as const;
    }),
  );
  certCache.set(teamDomain, { fetchedAt: Date.now(), certs });
  return certs;
}

/** Extract the Access JWT from the CF_Authorization session cookie, if present. */
function readAccessCookie(cookieHeader: string | null): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    if (part.slice(0, eq).trim() === "CF_Authorization") {
      return part.slice(eq + 1).trim();
    }
  }
  return null;
}

let warnedDevBypass = false;

export async function checkAccess(
  req: Request,
  env: AccessEnv,
  deps: {
    fetchCerts?: typeof fetchTeamCerts;
    nowSec?: number;
  } = {},
): Promise<AccessResult> {
  if (!env.teamDomain || !env.aud) {
    if (!warnedDevBypass) {
      warnedDevBypass = true;
      console.warn(
        "Access check bypassed: set ACCESS_TEAM_DOMAIN + ACCESS_AUD in prod (agent D).",
      );
    }
    return { ok: true, email: "local-dev", devBypass: true };
  }
  const headerToken = req.headers.get("Cf-Access-JWT-Assertion");
  const token = headerToken || readAccessCookie(req.headers.get("Cookie"));
  if (!token) return { ok: false, status: 401, message: "missing Access JWT" };
  const fetchCerts = deps.fetchCerts ?? fetchTeamCerts;
  let certs: Map<string, JsonWebKey>;
  try {
    certs = await fetchCerts(env.teamDomain);
  } catch {
    return { ok: false, status: 401, message: "cannot load Access certs" };
  }
  const nowSec = deps.nowSec ?? Math.floor(Date.now() / 1000);
  const result = await verifyAccessJwt(token, { certs, aud: env.aud, nowSec });
  if (!result.ok) {
    const status = result.message === "wrong audience" ? 403 : 401;
    return { ok: false, status, message: result.message };
  }
  return { ok: true, email: result.email, devBypass: false };
}
