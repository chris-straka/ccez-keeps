/* Lane E: app-shell Service Worker. Navigations go to the network first
   (falling back to cache offline) so an expired Access session redirects
   to login instead of serving a shell whose sync 401s forever; same-origin
   static assets stay cache-first (their filenames are content-hashed, so
   they can never go stale). /api/* always goes to the network (never serve
   or cache API responses — stale sync data is worse than an offline error
   the UI already handles). build.ts stamps CACHE per build so deploys
   propagate; "ccez-keeps-dev" below only runs unbuilt. */
const CACHE = "ccez-keeps-dev";
const APP_SHELL = [
  "/",
  "/index.html",
  "/styles.css",
  "/main.js",
  "/manifest.webmanifest",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET") return;
  if (url.pathname.startsWith("/api/")) return;
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request)
        .then((res) => {
          // Never cache the login bounce: after Access redirects an
          // expired session, the final response lives on the team domain.
          if (res.ok && new URL(res.url).origin === self.location.origin) {
            const copy = res.clone();
            caches.open(CACHE).then((cache) => cache.put(event.request, copy));
          }
          return res;
        })
        .catch(() =>
          caches.match(event.request).then((hit) => hit ?? caches.match("/")),
        ),
    );
    return;
  }
  event.respondWith(
    caches.match(event.request).then(
      (hit) =>
        hit ??
        fetch(event.request).then((res) => {
          if (res.ok && url.origin === self.location.origin) {
            const copy = res.clone();
            caches.open(CACHE).then((cache) => cache.put(event.request, copy));
          }
          return res;
        }),
    ),
  );
});
