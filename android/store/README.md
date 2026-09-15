# Play Store assets for Keeps

Everything a Play listing needs except the console clicks (see below).

## Layout

- `listing/en-US/` — title (≤30 chars), short description (≤80), full
  description. Paste each into Play Console → Main store listing.
- `graphics/icon-512.png` — high-res icon (512×512).
- `graphics/feature-graphic-1024x500.png` — feature graphic (1024×500).
- `graphics/screenshots/` — 4 phone screenshots (1080×2400, PNG).
- `tools/make_graphics.py` — regenerates the icon + feature graphic from
  `web/icon.svg` (no SVG rasterizer needed):
  `uv run --with pillow android/store/tools/make_graphics.py`

## Screenshot provenance

Real screenshots, not mockups: Pixel_8a emulator, app enrolled against a
local `wrangler dev`, demo notes pushed through the real sync API, each
screen verified via `uiautomator` hierarchy before capture.

1. `01-notes-grid.png` — populated notes grid (colors, pins, checklist,
   markdown, drawing thumbnail).
2. `02-note-editor.png` — edit dialog with formatting toolbar.
3. `03-drawing-canvas.png` — freehand drawing editor.
4. `04-sign-in.png` — first-launch enrollment screen (fresh install).

To re-shoot: build with `KEEPS_BASE_URL=http://10.0.2.2:8787`, run the
worker locally without `ACCESS_*` vars (local-dev auth bypass), mint a
code via `/api/devices/code?to=keeps://enroll`, deliver it with
`adb shell am start -a VIEW -d "keeps://enroll?code=..."`, seed notes
through `/api/notes/sync`, and `screencap` each state.

## Release checklist (console, manual)

1. Set `KEEPS_KEYSTORE_PATH/PASSWORD/ALIAS` (see README) and build the
   release AAB: `./gradlew :app:bundleRelease`.
2. Play Console → create app → upload the AAB; upload the graphics here.
3. Data safety: single-user app; note contents sync to the user's own
   backend for app functionality; no ads, no trackers, no third-party
   sharing. No privacy-policy URL is hosted — add one if review asks.
4. Content rating questionnaire, target-audience declaration, then
   review.
