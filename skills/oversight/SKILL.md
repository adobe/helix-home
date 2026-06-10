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

# Oversight â RUM / Operational Telemetry

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

- `--range=month` â Time range: `day`, `week`, `month`, `year` (default: `month`)
- `--date=YYYY-MM-DD` â Specific date for bundle fetch (default: today)
- `--limit=20` â Number of results for top-pages

## Architecture

- **API**: `https://bundles.aem.page`
- **Auth model**: Two-tier key system
  - **Admin key**: A token that can mint domain-specific keys. Stored locally
    and sent as `Authorization: Bearer <key>` to the `/domainkey/<domain>` endpoint.
  - **Domain key**: A per-domain token returned by the mint endpoint. Passed as
    `?domainkey=<key>` query parameter on bundle data requests.
- **Minting**: `POST /domainkey/<domain>` with Bearer admin key â creates a new
  domain key (201). `GET /domainkey/<domain>` with Bearer admin key â retrieves
  an existing domain key.
- **Data format**: Bundles are JSON arrays of sampled page-load events grouped
  by time slot. Each bundle has a `weight` field for extrapolation.
- **Sampling**: Data is sampled; always multiply by `weight` for accurate counts.
- **Granularity**: `/bundles/<domain>/YYYY/MM` (month), `/bundles/<domain>/YYYY/MM/DD` (day)

## Going beyond the CLI: `@adobe/rum-distiller`

The `oversight` CLI gives you the common queries (status, page views, vitals, top
pages). When the user asks for something the CLI doesn't expose â visits per
URL, custom facets, conversion rates, traffic-source breakdowns, histograms,
linear regression â drop into Node and use the
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

- `.sum` â weight-adjusted total (use this for visits / page views)
- `.count` â bundle count (use sparingly; prefer weighted sums)
- `.weight` â total weight (== `.sum` for boolean series)
- `.mean`, `.median`, `.stddev`, `.variance`, `.stderr`
- `.percentile(p)` â p-th percentile of values (use for `lcp`/`cls`/`inp` p75)
- `.share` â count / parent.count
- `.percentage` â sum / parent.sum

`dc.totals.<seriesName>` is the same aggregate object across the whole filtered
dataset.

### Filters

```javascript
dc.filter = { url: ['https://www.example.com/'], userAgent: ['mobile'] };
// Then read dc.totals / dc.facets â they recompute against the filter.
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

### Estimating unseen domains/URLs with Chao1

When you're working with a sampled bundle stream â most importantly the
multi-tenant `aem.live:all` aggregate, but also any narrow window or
low-traffic site â the count of distinct facet values you observe is a
*lower bound*. Items that exist but didn't fire enough events to clear the
bundler's per-event sampling threshold are simply absent.

`@adobe/rum-distiller@1.23.0` ships a non-parametric Chao1 estimator on every
facet to back out the unseen tail. Read it as
`dc.estimators.<facetName>.chao1`:

```javascript
const dc = new DataChunks();
dc.load([{ date: 'all', rumBundles: bundles }]);
dc.addSeries('pageViews', series.pageViews);
dc.addFacet('url', facets.url);

const est = dc.estimators.url.chao1;
console.log(est.sObs);    // observed distinct values
console.log(est.sHat);    // estimated true distinct values (Chao1)
console.log(est.sUnseen); // sHat - sObs
console.log(est.f1);      // singletons (seen exactly once)
console.log(est.f2);      // doubletons (seen exactly twice)
console.log(est.ci);      // [low, high] 95% CI for sHat
console.log(est.darkCI);  // 95% CI for sUnseen
```

Chao1 works on raw bundle counts (one observation = one bundle whose facet
includes the value), so it's most reliable when each facet value fires
independent events. It's a *conservative* estimator â when `f1` and `f2` are
both small (e.g. < 10), the CI is wide and you should treat the point
estimate as a soft lower bound.

Per-bucket Chao1 (e.g. "estimate distinct domains per `hostType`") is a
filter-and-rebuild operation:

```javascript
function chaoFor(predicate) {
  const sub = new DataChunks();
  sub.load([{ date: 'sub', rumBundles: bundles.filter(predicate) }]);
  sub.addSeries('pageViews', series.pageViews);
  sub.addFacet('url', facets.url);
  return sub.estimators.url.chao1;
}

for (const t of ['aemcs', 'ams', 'helix', 'commerce']) {
  const e = chaoFor(b => b.hostType === t);
  console.log(t, 'observed:', e.sObs, 'estimated:', Math.round(e.sHat),
              'CI:', e.ci.map(Math.round));
}
```

### Multi-tenant aggregates: `aem.live:all`

The bundler exposes a virtual `aem.live:all` stream that contains a sample
of `top` and CWV events from every domain reporting RUM, classified by
origin host into a `hostType` field. It is *the* place to ask
"across all of EDS / AEMCS / AMS / Commerce, how is X trending?"

Fetch it like any other domain (the explorer's domain key for `aem.live:all`
is the same shared key used by the OpTel UI):

```javascript
const KEY = '<aem.live:all domain key>';
const r = await fetch(
  `https://bundles.aem.page/bundles/aem.live:all/2026/05?domainkey=${KEY}`
);
```

Two things to know up front, both unintuitive:

**1. Use day granularity, not month, for windows wider than a day.** The
bundler caps each response at ~8k bundles, so a month-granularity fetch on
`aem.live:all` returns roughly **9Ã fewer bundles than the same period
fetched day-by-day**:

```
month  /bundles/aem.live:all/2026/04           â 8,155 bundles
day    /bundles/aem.live:all/2026/04/{01..30}  â 73,182 bundles
```

The OpTel Explorer's standard date range falls into this trap on `:all`
domains for ranges > 31 days â it uses the month branch and silently sees
~10% of the data. When you script the analysis yourself, paginate per-day:

```javascript
const all = [];
for (let d = 1; d <= 31; d++) {
  const dd = String(d).padStart(2, '0');
  const r = await fetch(
    `https://bundles.aem.page/bundles/aem.live:all/2026/05/${dd}?domainkey=${KEY}`
  );
  const j = await r.json();
  if (j.rumBundles) {
    for (const b of j.rumBundles) utils.addCalculatedProps(b);
    all.push(...j.rumBundles);
  }
}
```

(Months older than ~30 days return 413 Payload Too Large at month
granularity but work fine at day granularity, so day pagination is the only
way to get historical data anyway.)

**2. `hostType` is a host-suffix regex with `helix` as the catch-all.** The
classifier in `helix-rum-bundler/src/bundler/virtual.js` reads:

| `hostType` | matches origin host |
|---|---|
| `aemcs` | `*.adobeaemcloud.net` |
| `ams` | `*.adobecqms.net` |
| `commerce` | `*.adobecommerce.net` |
| `helix` | everything else (the fallthrough) |

So `helix` is **not** a synonym for "EDS-licensed". A licensed EDS site
whose origin host reports as the customer's own CDN/domain (which is most
of them in production) lands in `helix`; an AMS customer who has migrated
to AEMCS lands in `aemcs`. Treat the bucket names as origin-tier
classifications, not customer-tier engagements.

Also: `aem.live:all` retains only the `top` checkpoint and CWV checkpoints
(`cwv-lcp/cls/inp/ttfb/fid`). All click, view, enter, navigate, and consent
events are stripped. Don't try to compute visits, bounces, or acquisition
source from this aggregate â those series will return zero.

### Worked example: counting Adobe-served domains for a quarter

Putting it all together â fetch a quarter of `aem.live:all` data,
bucket by `hostType`, and apply Chao1 to get a defensible distinct-domain
estimate with a CI:

```javascript
const { DataChunks, series, facets, utils } =
  await import('https://esm.sh/@adobe/rum-distiller');

const KEY = '<aem.live:all domain key>';

// 1. Pull 92 days of bundles per-day. ~225k bundles for a quarter.
const all = [];
for (const [yr, mo, dmax] of [[2026,'03',31],[2026,'04',30],[2026,'05',31]]) {
  for (let d = 1; d <= dmax; d++) {
    const dd = String(d).padStart(2, '0');
    const r = await fetch(
      `https://bundles.aem.page/bundles/aem.live:all/${yr}/${mo}/${dd}?domainkey=${KEY}`
    );
    const j = await r.json();
    if (j.rumBundles) {
      for (const b of j.rumBundles) utils.addCalculatedProps(b);
      all.push(...j.rumBundles);
    }
  }
}

// 2. Per-hostType Chao1 on the url facet (which collapses to bundle.domain
//    when set, i.e. one facet value per public domain).
function chao(filter) {
  const sub = new DataChunks();
  sub.load([{ date: 'q', rumBundles: all.filter(filter) }]);
  sub.addSeries('pageViews', series.pageViews);
  sub.addFacet('url', facets.url);
  return sub.estimators.url.chao1;
}

for (const t of ['aemcs', 'ams', 'helix', 'commerce']) {
  const e = chao(b => b.hostType === t);
  console.log(`${t.padEnd(9)} observed=${e.sObs}  chao1=${Math.round(e.sHat)}  ` +
              `CI=[${Math.round(e.ci[0])} â ${Math.round(e.ci[1])}]`);
}
```

Sample output for Mar 1 â May 31, 2026:

```
aemcs     observed=4188  chao1=6603  CI=[6302 â 6946]
ams       observed=2271  chao1=3315  CI=[3140 â 3525]
helix     observed=644   chao1=1433  CI=[1207 â 1752]
commerce  observed=1543  chao1=2017  CI=[1911 â 2154]
```

The "observed" column is roughly 2Ã what you'd see in the OpTel Explorer
sidebar for the same window (because the explorer uses month granularity).
The Chao1 column corrects for the further sampling that happens at the
bundler's per-event probabilistic threshold.

### Sampling threshold details (for tuning)

`aem.live:all` keeps each event with probability roughly `(weight/100)/100`,
clamped to `[0.00001, 0.99]`. Concretely:

| original event weight | retention probability |
|---:|---:|
| 1 | 0.01% |
| 10 | 0.1% |
| 100 | 1% |
| 1,000 | 10% |
| 10,000+ | 99% |

The retained event is then re-weighted by `1 / (1 - threshold)` to keep
weighted sums unbiased. So site-level totals (`pageViews.sum`, etc.) are
correct; what's lost is *cardinality* â distinct values that never cleared
the threshold are gone, which is exactly what Chao1 estimates back.

Source: [`helix-rum-bundler/src/bundler/virtual.js`](https://github.com/adobe/helix-rum-bundler/blob/main/src/bundler/virtual.js)
and the `weightedThreshold` helper in `src/support/util.js`.

### Gotchas

- **Don't skip `utils.addCalculatedProps(b)`**  it walks `b.events`, sets
  `b.visit = true` when an `enter` checkpoint exists, and copies CWV values
  out of `cwv-lcp` / `cwv-cls` / `cwv-inp` events into `b.cwvLCP` / `b.cwvCLS` /
  `b.cwvINP`. Skip it and visits/vitals come back as 0/undefined.
- **The bundler API normalizes URLs**: numeric path segments  `<number>`,
  long hex  `<hex>`, UUIDs  `<uuid>`. Treat `facets.url` values as URL
  *patterns*, not literal URLs.
- **Each bundle has a `weight` field** (e.g. 100, 700, 1000) representing the
  inverse sampling rate. Series functions return `weight` (not 1) when their
  predicate matches  that's how `.sum` becomes the weight-adjusted estimate.
- **Use `--range=year` cautiously**  pulling 12 months of bundles for a busy
  domain can fetch tens of thousands of records. Prefer monthly `/YYYY/MM`
  endpoints in a loop so you can show progress and resume.
- **Top-level `await` works in `.mjs` files**, but `import x from '...'`
  syntax is rejected by the realm worker. Use `await import(...)` exclusively.
- **`fs.writeFile` is the global SLICC `fs`**, not `node:fs`. Don't try to
  `import('node:fs')`  that 404s in the realm. Just call `await fs.writeFile(...)`.

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
| **Distinct-domain count for `aem.live:all`** | rum-distiller (day pagination, see "Multi-tenant aggregates") |
| **Estimating unseen items beyond the sample** | rum-distiller + `dc.estimators.<facet>.chao1` |
| **Per-`hostType` breakdown** (aemcs/ams/helix/commerce) | rum-distiller (note `helix` is the catch-all) |

## Don't

- Don't use raw event counts  always weight-adjusted (`sum of weights`)
- Don't expose admin keys or domain keys in logs, commit messages, or PR descriptions
- Don't assume a domain key exists  mint one first if you get 401 on bundle fetches
- Don't confuse GET (retrieve existing key) with POST (mint new key) on `/domainkey/`
- Don't forget `utils.addCalculatedProps(b)` before loading bundles into `DataChunks` 
  visits and core-web-vitals series silently return zero/undefined without it
