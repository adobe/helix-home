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
klickhaus login

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
| `login` | Store ClickHouse credentials |
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

## Architecture

- **Database**: ClickHouse Cloud (`helix_logs_production`)
- **Auth**: Basic auth over HTTPS (credentials stored in `$HOME/.config/klickhaus/config.json` with mode 0600, outside the repository)
- **Sampling**: Rows are sampled — always uses `sum(weight)` not `count(*)`
- **Tables**: `delivery` (CDN edge), `admin` (admin service), `backend` (backend services), `da` (Document Authoring)
- **Key columns**: `timestamp`, `response.status`, `request.host`, `request.url`, `weight`

## Don't

- Don't use `count(*)` — always `sum(weight)` for accurate counts (data is sampled)
- Don't forget backticks around dotted column names in ClickHouse
- Don't query without a time filter — tables have 2-week TTL but are large
