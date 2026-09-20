# TODO

## CI deploy secrets (one-time setup)

`deploy.yml` is committed but cannot deploy until these exist
(repo Settings → Secrets → Actions):

- [ ] `CLOUDFLARE_API_TOKEN` — dash → My Profile → API Tokens → Create
  Token → "Edit Cloudflare Workers" template, **plus D1:Edit**.
- [ ] `CLOUDFLARE_ACCOUNT_ID` — `ee6937662d7aeda01d2a6f1f49a1168a`
  (also in any Workers dashboard URL, or `wrangler whoami`).

Until then, deploys are manual: `bun run build && wrangler deploy`.
The first deploy (manual or CI) turns on Workers Logs.
