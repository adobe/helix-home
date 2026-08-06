---
name: klickhaus
description: >-
  Use this when investigating CDN incidents, checking error rates, or drilling into AEM Edge Delivery Services logs via ClickHouse. Covers error breakdowns, time series, log inspection, and automated incident investigation. Triggers on requests like "check CDN errors", "what's failing", "investigate 5xx", "error rate", "klickhaus status", or when on-call and needing to understand traffic anomalies.
allowed-tools: bash
---

# Klickhaus — CDN Incident Investigation

CLI tool for querying AEM Edge Delivery Services CDN logs in ClickHouse. Designed for on-call engineers who need to quickly understand what's happening during an incident.

## Quick start

```bash
# First time — store credentials
klickhaus login --user=USERNAME --password=PASSWORD

# Or reuse an open, logged-in klickhaus dashboard tab (no manual creds):
klickhaus login --from-tab

# Is something wrong right now?
klickhaus status

# Drill into errors
klickhaus errors --range=1h --host=example.com

# See traffic shape
klickhaus timeseries --range=1h

# Break down by any dimension
klickhaus breakdown host --range=1h
klickhaus breakdown status --range=1h --host=example.com

# View individual log entries
klickhaus logs --status=5xx --limit=20

# Automatic investigation — finds top error contributors
klickhaus investigate --range=1h

# Monday protocol — summary for handoff
klickhaus monday --range=24h

# Run arbitrary SQL
klickhaus query "SELECT count() FROM delivery WHERE timestamp >= now() - INTERVAL 1 HOUR"
klickhaus query --file=query.sql
cat query.sql | klickhaus query
```

## Available commands

| Command | Purpose |
|---------|---------|
| `login` | Store ClickHouse credentials (`--user`/`--password`, or `--from-tab` to reuse a logged-in dashboard tab) |
| `status` | Quick health check: total requests, error rates, top error hosts (last hour) |
| `errors` | Error breakdown by host, path, status code |
| `timeseries` | Time series of ok/4xx/5xx traffic |
| `breakdown <dim>` | Top N values for any dimension with ok/4xx/5xx counts |
| `logs` | Individual log entries matching filters |
| `investigate` | Automatic multi-dimension error analysis |
| `monday` | Monday protocol output for handoff |
| `query` | Run arbitrary SQL — pass as argument or pipe through stdin |

## Common flags

- `--table=delivery` — Table to query: `delivery`, `admin`, `backend`, `da` (default: `delivery`)
- `--range=1h` — Time range: `15m`, `1h`, `12h`, `24h`, `3d`, `7d` (default: `1h`)
- `--host=example.com` — Filter to specific host
- `--status=5xx` — Filter by status range (`4xx`, `5xx`) or exact code (`503`)
- `--limit=20` — Number of results

## Dimensions for breakdown

`host`, `status`, `path`, `content_type`, `cache`, `method`, `request_type`, `backend_type`, `forwarded_host`, `referer`, `user_agent`, `datacenter`, `asn`, `ip`

## Incident RCA playbook

Fast root-cause routine for a "More than 100 5xx on aem.(page|live) in 10m" style alert:

1. **Find the burst, not the average.** The alert is about a ~10-minute window; the `1h` rollup hides a short spike. Bucket per-minute to locate the exact minute(s) and to confirm recovery:
   `klickhaus query "SELECT toStartOfMinute(timestamp) m, sum(weight) c FROM delivery WHERE timestamp >= now()-INTERVAL 30 MINUTE AND intDiv(\`response.status\`,100)=5 GROUP BY m ORDER BY m"`
2. **`x_error` is the #1 discriminator.** Break down `response.headers.x_error` for the 5xx — it maps almost 1:1 to a subsystem:
   - `first byte timeout` / `[pipeline: html] first byte timeout` → rendering **pipeline** slow/unresponsive.
   - `All backends failed or unhealthy (chash)` → **Fastly** director found all backends unhealthy → usually a **PoP-level network/reachability** problem (confirm it's localized — see below).
   - `[media] Service Unavailable` / `[media] [IO] Error transforming image` → **media/image** backend.
   - `Worker: x-error: failed to load … from content-bus: 500` → **content-bus**.
   - `[static: …] R2: …` → **R2** storage.
   - `Worker: Service Unavailable` on `main--project-elmo-ui-data--adobe.aem.live` → known **Project Elmo** huge-JSON worker OOM (already excluded from the trigger).
3. **Localize by datacenter.** `breakdown datacenter` (or group by `cdn.datacenter`) on the 5xx. All errors in ONE PoP while others are clean = a **PoP/network** issue, not a backend outage. `KUL, KNU, VNS, IXD, BOM, SIN, FRA, IAD…` are **Fastly** PoP codes.
4. **Scope by tenant.** `breakdown host`: many unrelated tenants → **infrastructure**; a single host → **customer/app**.
5. **Confirm recovery / recurrence.** Re-poll per-minute (mind the ingest lag, below); check the last 24h by `datacenter`+hour to see whether the exact `x_error` is a known recurring regional pattern.

**CDN layering (don't conflate):** the `delivery` edge is **Fastly** (note the `cdn.fastly_error` column, and the Fastly PoP codes in `cdn.datacenter`). `helix.backend_type` (`aws`/`cloudflare`) is the **origin type behind** Fastly, NOT the CDN. So a `backends failed (chash)` error with `backend_type=aws` is a **Fastly-edge→origin** reachability failure, not an AWS outage.

**Known non-incidents already excluded from the alert trigger:** Project Elmo huge-JSON worker OOM (503 `Worker: Service Unavailable`), and Cloudflare `530`s from the `websiphon-x2` scanner (UA blocked at the edge).

## Architecture

- **Database**: ClickHouse Cloud (`helix_logs_production`)
- **Auth**: Basic auth over HTTPS. Credentials are stored via the per-skill config bridge (a gitignored `.config` next to the skill). If the config is empty, the tool auto-detects credentials from a logged-in `klickhaus.aemstatus.net` browser tab (localStorage key `clickhouse_credentials`) and logs in transparently — so `status`/`errors`/etc. just work when the dashboard is open.
- **Sampling**: Rows are sampled — always uses `sum(weight)` not `count(*)`
- **Tables**: `delivery` (CDN edge), `admin` (admin service), `backend` (backend services), `da` (Document Authoring)
- **Key columns**: `timestamp`, `response.status`, `request.host`, `request.url`, `weight`

## Don't

- Don't use `count(*)` — always `sum(weight)` for accurate counts (data is sampled)
- Don't forget backticks around dotted column names in ClickHouse
- Don't query without a time filter — tables have 2-week TTL but are large
- Don't append `FORMAT JSON` to `klickhaus query` SQL — the command adds it for you (a trailing `FORMAT` is a syntax error)
- Don't read the freshest 1–2 minutes as gospel — ingestion lags ~1–2 min, so the latest minute(s) may be empty or partial; confirm recovery against minutes a couple back
- Don't conflate `helix.backend_type` (origin behind Fastly) with the CDN edge (Fastly) — see the RCA playbook
