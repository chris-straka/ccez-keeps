# Cloudflare Access setup (dashboard config, not in code)

The worker verifies identity itself (`worker/auth.ts`); these three Access
applications shape what credentials ever reach it. If sync auth breaks,
read this before touching code.

Team domain: `silent-bread-ade3.cloudflareaccess.com`

## Applications

| App | Domain | Policy | Purpose |
| --- | --- | --- | --- |
| `ccez-keeps` | `keeps.cstraka.dev` | `owner-only` (allow) | Login gate for the app shell. Its AUD is `ACCESS_AUD` in `wrangler.jsonc`. |
| `ccez-keeps-api` | `keeps.cstraka.dev/api/*` (+ `ccez-keeps.chris-e69.workers.dev/api/*`) | `api-worker-gate` (**bypass**, everyone) | Lets API traffic through without an Access session; the worker does the real check. Side effect: Access never appends `Cf-Access-JWT-Assertion` on this path — that is why the worker also accepts the cookie (below). Do not "fix" this to Allow without also handling the header. |
| `ccez-keeps-manifest` | `keeps.cstraka.dev/manifest.webmanifest` | `public-manifest` (**bypass**, everyone) | Browsers fetch the manifest credentialless, so an Allow policy redirects it to login and CORS-blocks PWA installability. |

## How the worker verifies (fail-closed)

1. `Cf-Access-JWT-Assertion` header, else `CF_Authorization` session cookie
   (same JWT; the cookie is the fallback because the api bypass strips the
   header). Neither → 401 `missing Access JWT`.
2. RS256 signature against `https://<team>/cdn-cgi/access/certs` (6h cache).
3. Expiry, then audience: `aud` may be a string **or an array** (Access
   mints an array) — the app AUD must be present, else 403 `wrong audience`.
   Tokens for other apps in the same account share the team certs and are
   rejected here.
4. Non-empty `email` claim.

## If you recreate an Access application

The AUD tag changes. Copy the new **Application Audience (AUD) Tag** from
Zero Trust → Access → Applications → app → Additional settings into
`ACCESS_AUD` in `wrangler.jsonc`, then redeploy. Stale AUD → every API call
403s with `wrong audience`.

## Debugging 401s/403s

Open the failing request → Response tab shows the exact cause
(`missing Access JWT`, `cannot load Access certs`, `expired`,
`wrong audience`, …). Device tokens (`Authorization: Bearer`) bypass this
gate; see the enroll flow in `worker/devices.ts`.
