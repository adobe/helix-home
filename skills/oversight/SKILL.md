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

## Going beyond the CLI: `@adobe/rum-distiller`

The `oversight` CLI gives you the common queries (status, page views, vitals, top
pages). When the user asks for something the CLI doesn't expose — visits per
URL, custom facets, conversion rates, traffic-source breakdowns, histograms,
linear regression — drop into Node and use the
[`@adobe/rum-distiller`](https://www.npmjs.com/package/@adobe/rum-distiller)
library directly. It's the same library the OpTel Explorer uses, so the numbers
will match what customers see in the UI.

### Loading the library

In SLICC's Node shim, top-level `await` is supported in `.mjs` files but
synchronous `import` statements are not. Use a dynamic `import()` instead:

```javascript
// /tmp/rum.mjs
const { DataChunks, series, facets, utils } = await import('https://esm.sh/@adobe/rum-distiller');
```

Then run with `node /tmp/rum.mjs`. The realm worker will keep the event loop
alive long enough for fetches to resolve.

### The complete flow

```javascript
const { DataChunks, series, facets, utils } = await import('https://esm.sh/@adobe/rum-distiller');

const DOMAIN = 'www.example.com';
const KEY = '<DOMAINKEY-from-oversight-mint>';

// 1. Fetch bundles. Use month granularity for ranges > a few days,
//    day granularity for narrow windows. Each call returns { rumBundles: [...] }.
const allBundles = [];
for (const m of ['2026/05', '2026/04', '2026/03']) {
  const r = await fetch(`https://bundles.aem.page/bundles/${DOMAIN}/${m}?domainkey=${KEY}`);
  const j = await r.json();
  if (j.rumBundles) {
    // 2. CRITICAL: addCalculatedProps populates s.visit, s.cwvLCP, s.cwvCLS, etc.
    //    Without this, series.visits / series.lcp / series.cls / series.inp all return 0.
    for (const b of j.rumBundles) utils.addCalculatedProps(b);
    allBundles.push(...j.rumBundles);
  }
}

// 3. Wrap in DataChunks. Note the [{ date, rumBundles }] shape.
const dc = new DataChunks();
dc.load([{ date: 'all', rumBundles: allBundles }]);

// 4. Register the series (metrics) you care about.
dc.addSeries('pageViews', series.pageViews);
dc.addSeries('visits',    series.visits);
dc.addSeries('lcp',       series.lcp);
dc.addSeries('cls',       series.cls);
dc.addSeries('inp',       series.inp);
dc.addSeries('engagement', series.engagement);
dc.addSeries('bounces',   series.bounces);

// 5. Register the facets (groupings) you care about.
dc.addFacet('url',         facets.url);          // pathname-normalized URLs
dc.addFacet('plainURL',    facets.plainURL);     // full URLs minus query/hash
dc.addFacet('userAgent',   facets.userAgent);    // mobile / desktop / mobile:ios / ...
dc.addFacet('checkpoint',  facets.checkpoint);   // event types in the bundle
dc.addFacet('vitals',      facets.vitals);       // good / ni / poor per CWV

// 6. Read the totals (site-wide) and per-facet metrics.
console.log('total page views:', dc.totals.pageViews.sum);
console.log('total visits:    ', dc.totals.visits.sum);
console.log('LCP p75:', dc.totals.lcp.percentile(75));

for (const f of dc.facets.url.slice(0, 20)) {
  console.log(f.value, f.metrics.pageViews.sum, f.metrics.visits.sum);
}
```

### What's available

**Series (metrics)** from `series.*`:
`pageViews`, `visits`, `bounces`, `organic`, `earned`, `engagement`, `lcp`,
`cls`, `inp`, `ttfb`.

**Facets (groupings)** from `facets.*`:
`url`, `plainURL`, `userAgent`, `checkpoint`, `vitals`, `lcpTarget`, `lcpSource`,
`acquisitionSource`, `enterSource`, `mediaTarget`.

**Utility helpers** from `utils.*`:
`addCalculatedProps` (always run on raw bundles before loading), `scoreCWV`,
`scoreBundle`, `toHumanReadable`, `classifyAcquisition`, `reclassifyAcquisition`.

**Statistical helpers** from `stats.*`:
`zTestTwoProportions`, `linearRegression`, `roundToConfidenceInterval`, `tTest`,
`samplingError`. Useful for "is variant A faster than variant B at p < 0.05?"
and confidence-interval framing.

### Per-facet aggregate API

Each entry in `dc.facets.<name>` exposes `.metrics.<seriesName>`, which is an
aggregate object with:

- `.sum` — weight-adjusted total (use this for visits / page views)
- `.count` — bundle count (use sparingly; prefer weighted sums)
- `.weight` — total weight (== `.sum` for boolean series)
- `.mean`, `.median`, `.stddev`, `.variance`, `.stderr`
- `.percentile(p)` — p-th percentile of values (use for `lcp`/`cls`/`inp` p75)
- `.share` — count / parent.count
- `.percentage` — sum / parent.sum

`dc.totals.<seriesName>` is the same aggregate object across the whole filtered
dataset.

### Filters

```javascript
dc.filter = { url: ['https://www.example.com/'], userAgent: ['mobile'] };
// Then read dc.totals / dc.facets — they recompute against the filter.
```

Filter values are arrays; the default combiner is `some` (OR within a facet,
AND across facets). Pass a 3rd argument to `addFacet` (`'every'`, `'none'`,
`'never'`) for non-default semantics.

### Histograms and clusters

```javascript
dc.addFacet('url', facets.url);                 // base facet first
dc.addHistogramFacet('lcpHist', 'lcp', { count: 10, steps: 'logarithmic' });
dc.addClusterFacet('urlPath',  'url', { count: 5 });   // most-common path prefixes
```

### Gotchas

- **Don't skip `utils.addCalculatedProps(b)`** — it walks `b.events`, sets
  `b.visit = true` when an `enter` checkpoint exists, and copies CWV values
  out of `cwv-lcp` / `cwv-cls` / `cwv-inp` events into `b.cwvLCP` / `b.cwvCLS` /
  `b.cwvINP`. Skip it and visits/vitals come back as 0/undefined.
- **The bundler API normalizes URLs**: numeric path segments → `<number>`,
  long hex → `<hex>`, UUIDs → `<uuid>`. Treat `facets.url` values as URL
  *patterns*, not literal URLs.
- **Each bundle has a `weight` field** (e.g. 100, 700, 1000) representing the
  inverse sampling rate. Series functions return `weight` (not 1) when their
  predicate matches — that's how `.sum` becomes the weight-adjusted estimate.
- **Use `--range=year` cautiously** — pulling 12 months of bundles for a busy
  domain can fetch tens of thousands of records. Prefer monthly `/YYYY/MM`
  endpoints in a loop so you can show progress and resume.
- **Top-level `await` works in `.mjs` files**, but `import x from '...'`
  syntax is rejected by the realm worker. Use `await import(...)` exclusively.
- **`fs.writeFile` is the global SLICC `fs`**, not `node:fs`. Don't try to
  `import('node:fs')` — that 404s in the realm. Just call `await fs.writeFile(...)`.

### When to reach for rum-distiller vs. the CLI

| Need | Use |
|------|-----|
| Quick traffic check | `oversight status <domain>` |
| Top pages by page views | `oversight top-pages <domain>` |
| Daily / weekly trend | `oversight pageviews <domain>` |
| **Visits per URL** (not in CLI) | rum-distiller |
| **Visits per acquisition source** | rum-distiller |
| **Custom facet** (e.g. `url:exclude-blog`) | rum-distiller |
| **Histogram** (LCP distribution, not just p75) | rum-distiller |
| **Funnel / conversion** between checkpoints | rum-distiller |
| **A/B significance** for two URL variants | rum-distiller + `stats.zTestTwoProportions` |
| **Linear-regression trend** over a series | rum-distiller + `stats.linearRegression` |

## Don't

- Don't use raw event counts — always weight-adjusted (`sum of weights`)
- Don't expose admin keys or domain keys in logs, commit messages, or PR descriptions
- Don't assume a domain key exists — mint one first if you get 401 on bundle fetches
- Don't confuse GET (retrieve existing key) with POST (mint new key) on `/domainkey/`
- Don't forget `utils.addCalculatedProps(b)` before loading bundles into `DataChunks` —
  visits and core-web-vitals series silently return zero/undefined without it
