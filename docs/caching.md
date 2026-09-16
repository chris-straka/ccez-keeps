# Caching & deploys

## Why bundle filenames are hashed

The Service Worker (`web/sw.js`) precaches the app shell **cache-first**.
With stable filenames, a returning browser keeps serving the old `main.js`
from Cache Storage after a deploy: the first reload installs the new worker
in the background while still running old code, and if the `sw.js` update
check itself is HTTP-cached, the swap may never happen. Symptom: DevTools
console errors referencing old `main.js` line numbers right after a deploy.

`build.ts` fixes this by fingerprinting content into filenames —
`main.<12-hex>.js`, `main.<12-hex>.js.map`, `styles.<12-hex>.css` — and
rewriting the references in `dist/index.html` and the `APP_SHELL` list in
`dist/sw.js`. A new deploy is new URLs, so no cache (Service Worker or
HTTP) can ever serve mismatched code. The per-build `CACHE` stamp in
`sw.js` remains as belt-and-braces.

`dist/` is pure build output: the build wipes it first so hashed files
from previous builds never accumulate.

## If a tab still looks stale after a deploy

`/api/*` responses are never cached (the worker passes them to the
network), so server-side fixes take effect on the next sync round with a
plain reload. If the *UI itself* looks old, the tab is pinned to a stale
worker: close every `keeps.cstraka.dev` tab and reopen, or DevTools →
Application → Storage → Clear site data, then reload.
