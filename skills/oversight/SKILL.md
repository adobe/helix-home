---
name: oversight
description: >-
  Query AEM Edge Delivery Services Real User Monitoring (RUM) data via the
  Operational Telemetry (OpTel) bundler API. Covers page views, web vitals,
  traffic breakdowns, and domain key management. Use when investigating site
  performance, checking page view trends, analyzing Core Web Vitals, or
  exploring RUM data for an EDS domain. Triggers on requests like "how many
  page views", "check web vitals", "RUM data for", "traffic for",
  "oversight status", "mint a domain key", or "what's the LCP on".
allowed-tools: bash
---

# Oversight — RUM / Operational Telemetry

CLI tool for querying AEM Edge Delivery Services Real User Monitoring data
via the bundler API at `bundles.aem.page`.

## Quick start

```bash
# Store your admin key (a RUM bundler admin token)
oversight login --key=<ADMIN_KEY>

# Mint a domain key for a new domain (requires admin key)
oversight mint example.com

# Quick traffic overview for a domain
oversight status example.com

# Page views over the last month
oversight pageviews example.com --range=month

# Core Web Vitals summary
oversight vitals example.com

# Top pages by traffic
oversight top-pages example.com --limit=20

# Raw bundle data for a specific day
oversight bundles example.com --date=2026-05-28

# List all domain keys you've minted this session
oversight keys
```

## Available commands

| Command | Purpose |
|---------|---------|
| `login` | Store admin key for domain key minting |
| `mint <domain>` | Mint (POST) or retrieve (GET) a domain key |
| `keys` | List cached domain keys |
| `status <domain>` | Quick overview: page views, visits, engagement, vitals |
| `pageviews <domain>` | Page view time series |
| `vitals <domain>` | Core Web Vitals (LCP, CLS, INP) at p75 |
| `top-pages <domain>` | Top URLs by page views |
| `bundles <domain>` | Fetch raw bundle data for a date |

## Common flags

- `--range=month` — Time range: `day`, `week`, `month`, `year` (default: `month`)
- `--date=YYYY-MM-DD` — Specific date for bundle fetch (default: today)
- `--limit=20` — Number of results for top-pages

## Architecture

- **API**: `https://bundles.aem.page`
- **Auth model**: Two-tier key system
  - **Admin key**: A token that can mint domain-specific keys. Stored locally
    and sent as `Authorization: Bearer <key>` to the `/domainkey/<domain>` endpoint.
  - **Domain key**: A per-domain token returned by the mint endpoint. Passed as
    `?domainkey=<key>` query parameter on bundle data requests.
- **Minting**: `POST /domainkey/<domain>` with Bearer admin key → creates a new
  domain key (201). `GET /domainkey/<domain>` with Bearer admin key → retrieves
  an existing domain key.
- **Data format**: Bundles are JSON arrays of sampled page-load events grouped
  by time slot. Each bundle has a `weight` field for extrapolation.
- **Sampling**: Data is sampled; always multiply by `weight` for accurate counts.
- **Granularity**: `/bundles/<domain>/YYYY/MM` (month), `/bundles/<domain>/YYYY/MM/DD` (day)

## Don't

- Don't use raw event counts — always weight-adjusted (`sum of weights`)
- Don't expose admin keys or domain keys in logs, commit messages, or PR descriptions
- Don't assume a domain key exists — mint one first if you get 401 on bundle fetches
- Don't confuse GET (retrieve existing key) with POST (mint new key) on `/domainkey/`
