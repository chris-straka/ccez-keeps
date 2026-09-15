// Phase 0 build (+E): bundle the SPA to dist/ and copy static files.
// Lane E additions: manifest, Service Worker, icons.
import { cpSync, mkdirSync } from "node:fs";

mkdirSync("dist", { recursive: true });

const result = await Bun.build({
  entrypoints: ["web/main.ts"],
  outdir: "dist",
  target: "browser",
  minify: false,
  sourcemap: "external",
});

if (!result.success) {
  for (const log of result.logs) console.error(log);
  process.exit(1);
}

for (const file of [
  "index.html",
  "styles.css",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
]) {
  cpSync(`web/${file}`, `dist/${file}`);
}

// Stamp the SW cache name per build: the worker precaches shell URLs
// cache-first, so without a byte-change here returning browsers would
// serve the old bundle forever after a deploy.
const stamp = Date.now().toString(36);
const sw = await Bun.file("web/sw.js").text();
const stamped = sw.replace(/const CACHE = "[^"]*";/, `const CACHE = "ccez-keeps-${stamp}";`);
if (stamped === sw) throw new Error("build: CACHE stamp target missing in web/sw.js");
await Bun.write("dist/sw.js", stamped);
console.log("build ok -> dist/");
