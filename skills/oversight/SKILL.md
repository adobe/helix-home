---
name: oversight
description: >-
  Query AEM Edge Delivery Services Real User Monitoring (RUM) data via the
  Operational Telemetry (OpTel) bundler API. Covers page views, web vitals,
  traffic breakdowns, and domain key management. Use when investigating site
  performance, checking page view trends, analyzing Core Web Vitals, or
  exploring RUM data for an EDS domain. Triggers on requests like "how many
  page views", "check web vitals", "RUM data for", "traffic for",
  "oversight status", "mint a domain key", "rotate a domain key",
  or "what's the LCP on".
allowed-tools: bash
---

# Oversight — RUM / Operational Telemetry

CLI tool for querying AEM Edge Delivery Services Real User Monitoring data
via the bundler API at `bundles.aem.page`.

## Quick start

```bash
# Store your admin key (a RUM bundler admin token)
oversight login --key=<ADMIN_KEY>

# Mint (retrieve-or-create) a domain key. Prints a fingerprint, not the full key.
oversight mint example.com

# Replace a compromised domain key (POST, skips GET)
oversight rotate example.com

# Quick traffic overview for a domain
oversight status example.com
oversight status www.aem.live

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
| `mint <domain>` | Retrieve (GET) or create (POST) a domain key. Fingerprint only, unless `--show` |
| `rotate <domain>` | Replace a domain key (POST, skips GET). Use when a key is compromised |
| `keys` | List cached domain keys (fingerprints) |
| `status <domain>` | Quick overview: page views, visits, engagement, vitals |
| `pageviews <domain>` | Page view time series |
| `vitals <domain>` | Core Web Vitals (LCP, CLS, INP) at p75. Check `sampleSize`, and see [Reading LCP honestly](#reading-lcp-honestly) before quoting LCP |
| `top-pages <domain>` | Top URLs by page views |
| `bundles <domain>` | Fetch raw bundle data for a date |

## Common flags

- `--range=month` — Time range: `day`, `week`, `month`, `year` (default: `month`)
- `--date=YYYY-MM-DD` — Specific date for bundle fetch (default: today)
- `--limit=20` — Number of results for top-pages
- `--show` — Print the full domain key from `mint` / `rotate` (off by default)

## Architecture

- **API**: `https://bundles.aem.page`
- **Auth model**: Two-tier key system
  - **Admin key**: A token that can mint domain-specific keys. Stored locally
    and sent as `Authorization: Bearer <key>` to the `/domainkey/<domain>` endpoint.
  - **Domain key**: A per-domain token returned by the mint endpoint. Passed as
    `?domainkey=<key>` query parameter on bundle data requests.
- **Minting**: `oversight mint <domain>` is retrieve-or-create: GET first, POST
  only if GET yields nothing. `POST /domainkey/<domain>` with Bearer admin key
  creates **or replaces** a domain key (201). `GET /domainkey/<domain>` retrieves
  the current key without replacing it.
- **Rotation**: `oversight rotate <domain>` POSTs immediately (skips GET) and
  updates the cached key. Use this when a domain key has leaked; the previous
  key stops working. Like `mint`, it prints a fingerprint unless you pass `--show`.
- **Data format**: Bundles are JSON arrays of sampled page-load events grouped
  by time slot. Each bundle has a `weight` field for extrapolation.
- **Sampling**: Data is sampled; always multiply by `weight` for accurate counts.
- **Granularity**: `/bundles/<domain>/YYYY/MM` (month), `/bundles/<domain>/YYYY/MM/DD` (day)

## Synthetic aggregate domains

A domain containing a colon — for example `aem.live:all` — is a **synthetic
aggregate**. It rolls up many AEM sites rather than describing one site. The
real domain for the AEM/Helix website is `www.aem.live`; `aem.live:all` is the
aggregate across all AEM sites.

Most checkpoints are not collected on these aggregates (only `top` and CWV
`cwv-lcp` / `cwv-cls` / `cwv-inp` / `cwv-ttfb` / `cwv-fid`). Metrics that need
`enter`, `click`, `viewblock`, `viewmedia`, or other stripped checkpoints
**cannot be computed**. `oversight status` reports `visits`, `engagement`, and
`engagementRate` as `n/a (synthetic aggregate domain)` — never as `0` / `0%`.
Page views and Core Web Vitals still work.

Do not query a `:all` aggregate when the user asked about the AEM website
itself; use `www.aem.live`.

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

**Facet factories** from `facetFns.*` — `checkpointSource(cp)` and
`checkpointTarget(cp)` build a facet over the `source` / `target` of one
checkpoint. These are how you turn raw checkpoints into behaviour, and they
answer questions no built-in facet covers:

```javascript
import { facetFns } from '@adobe/rum-distiller/facets.js';

dc.addFacet('model',      facetFns.checkpointTarget('formsubmit'));
dc.addFacet('clicked',    facetFns.checkpointSource('click'));
dc.addFacet('failStatus', facetFns.checkpointTarget('missingresource'));
dc.addFacet('errMessage', facetFns.checkpointTarget('error'));
```

What `source` and `target` mean is per-checkpoint, and not guessable — read it
off the data before interpreting:

| checkpoint | `source` | `target` |
| --- | --- | --- |
| `click` | element / component selector | destination URL (if a navigation) |
| `viewblock` | block or panel name | — |
| `viewmedia` | container element | media URL |
| `missingresource` | the URL that failed | **HTTP status** (`500`, `410`, ...) |
| `error` | error type / family | error message |
| `enter`, `reload`, `back_forward` | referrer (`enter`) or page URL | **visibility** (`visible` / `hidden`) |
| `loadresource` | the fetched endpoint | number of resources |
| `a11y` | severity (`off` / `low` / `medium` / `high`) | the severity scale |
| `formsubmit` | form / surface name | (app-defined; e.g. the handler) |
| `language` | language | full locale (`en-US`) |
| `cwv-lcp` | the LCP element | — |
| `cwv-cls` | the shifting element | — |

Checkpoint names beyond the standard set are app-defined, so treat this table as
a starting point and confirm against
`dc.addFacet('cp', facets.checkpoint)` for the site you are looking at.

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

### Reading LCP honestly

LCP is the CWV metric most likely to make you report a regression that isn't
there. Two independent traps, both measured on a real app domain (795 bundles
over four months, 119 of them carrying `cwv-lcp`):

**1. A small sample makes LCP meaningless, and the CLI will not warn you.**
On a low-traffic domain, `oversight vitals` reported `lcp 6.99s` scored *poor*
for a month whose `sampleSize` was **8** — resting on exactly two LCP
observations, 1,160 ms and 8,416 ms. The same domain over a year (`sampleSize`
122) reported **1.50 s**, scored *good*. One slow load produced an apparent
4.7x regression. **Always read `sampleSize` before quoting a vitals number**, and
prefer a wider `--range` on a quiet site rather than believing the narrow one.

**2. Sessions with no interaction carry an unbounded LCP tail.**
LCP is finalized at the first user interaction (or when the page is hidden). A
session that renders and is then left alone keeps accumulating, so its reported
LCP is a measure of *how long the tab sat there*, not of load performance.
Grouping the same dataset by click count:

| group | bundles | p75 | p90 | max |
| --- | --- | --- | --- | --- |
| all (what `vitals` reports) | 795 | 7,876 ms | 36,636 ms | **2,843,752 ms** |
| `clicks >= 1` | 146 | 7,136 ms | 16,600 ms | 628,576 ms |
| `clicks >= 3` | 114 | 6,618 ms | 13,206 ms | 366,732 ms |

A p90 of 36.6 s and a max of **47 minutes** are not page loads. Restricting to
sessions with real interaction cuts p90 by 64% and leaves p75 close to where it
was — i.e. the tail is noise, the p75 is roughly honest, and **the mean is
worthless**. CWV is judged at p75 for exactly this reason; never quote a mean.

```javascript
// Interaction-gated LCP. facets/series as usual, then filter by a facet you add:
dc.addFacet('interacted', (bundle) =>
  bundle.events.some((e) => e.checkpoint === 'click') ? ['yes'] : ['no']);
dc.filter = { interacted: ['yes'] };
console.log('LCP p75 (interacted only):', dc.totals.lcp.percentile(75));
```

**What does NOT work: filtering on entry visibility.** It is tempting to blame
background/hidden tab opens, and to filter them out with
`checkpointTarget('enter') === 'hidden'`. Measured on the same dataset, that
filter is a **no-op for LCP**:

| entry visibility | bundles | with `cwv-lcp` |
| --- | --- | --- |
| `hidden` | 121 | **0 (0.0%)** |
| `visible` | 240 | 114 (47.5%) |
| no `enter`/`reload` event | 434 | 5 (1.2%) |

`web-vitals` already suppresses LCP for a page that starts hidden, so those
bundles never contribute an LCP value and there is nothing to exclude. The
multi-minute outliers all sit in the `visible` group — they are *foreground*
sessions that were never interacted with. Gate on interaction, not visibility.

### Histograms and clusters

```javascript
dc.addFacet('url', facets.url);                 // base facet first
dc.addHistogramFacet('lcpHist', 'lcp', { count: 10, steps: 'logarithmic' });
dc.addClusterFacet('urlPath',  'url', { count: 5 });   // most-common path prefixes
```

### Estimating unseen domains/URLs with Chao1

When you're working with a sampled bundle stream — most importantly the
multi-tenant `aem.live:all` aggregate, but also any narrow window or
low-traffic site — the count of distinct facet values you observe is a
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
independent events. It's a *conservative* estimator — when `f1` and `f2` are
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
bundler caps each response, so a month-granularity fetch on `aem.live:all`
returns only a fraction of the bundles you'd get fetching the same period
day-by-day:

```
month  /bundles/aem.live:all/YYYY/MM           → hits the per-response cap
day    /bundles/aem.live:all/YYYY/MM/{01..31}  → full period
```

The OpTel Explorer's standard date range falls into this trap on `:all`
domains for ranges > 31 days — it uses the month branch and silently sees
a small slice of the data. When you script the analysis yourself, paginate
per-day:

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
events are stripped. Don't compute visits, bounces, engagement, or acquisition
source from this aggregate — those series are unavailable (the CLI reports
`n/a (synthetic aggregate domain)`, never a misleading `0`). See **Synthetic
aggregate domains** above.

### Worked example: counting Adobe-served domains for a quarter

Putting it all together — fetch a quarter of `aem.live:all` data,
bucket by `hostType`, and apply Chao1 to get a defensible distinct-domain
estimate with a CI:

```javascript
const { DataChunks, series, facets, utils } =
  await import('https://esm.sh/@adobe/rum-distiller');

const KEY = '<aem.live:all domain key>';

// 1. Pull 92 days of bundles per-day.
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
              `CI=[${Math.round(e.ci[0])} – ${Math.round(e.ci[1])}]`);
}
```

Sample output (placeholders — do not paste real aggregate figures):

```
aemcs     observed=<sObs>  chao1=<sHat>  CI=[<low> – <high>]
ams       observed=<sObs>  chao1=<sHat>  CI=[<low> – <high>]
helix     observed=<sObs>  chao1=<sHat>  CI=[<low> – <high>]
commerce  observed=<sObs>  chao1=<sHat>  CI=[<low> – <high>]
```

The "observed" column is higher than the OpTel Explorer sidebar for the same
window (because the explorer uses month granularity). The Chao1 column
corrects for the further sampling that happens at the bundler's per-event
probabilistic threshold.

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
correct; what's lost is *cardinality* — distinct values that never cleared
the threshold are gone, which is exactly what Chao1 estimates back.

Source: [`helix-rum-bundler/src/bundler/virtual.js`](https://github.com/adobe/helix-rum-bundler/blob/main/src/bundler/virtual.js)
and the `weightedThreshold` helper in `src/support/util.js`.

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
| **Distinct-domain count for `aem.live:all`** | rum-distiller (day pagination, see "Multi-tenant aggregates") |
| **Estimating unseen items beyond the sample** | rum-distiller + `dc.estimators.<facet>.chao1` |
| **Per-`hostType` breakdown** (aemcs/ams/helix/commerce) | rum-distiller (note `helix` is the catch-all) |

## Don't

- Don't use raw event counts — always weight-adjusted (`sum of weights`)
- Don't expose admin keys or domain keys in logs, commit messages, or PR descriptions.
  `mint` and `rotate` print an 8-character fingerprint by default; only `--show`
  prints the full value, and never copy that into a transcript or PR
- Don't assume a domain key exists — mint one first if you get 401 on bundle fetches
- Don't confuse GET (retrieve existing key) with POST (mint/replace key) on
  `/domainkey/`. Plain `oversight mint <domain>` is retrieve-or-create (GET first).
  If a domain key is compromised, `oversight rotate <domain>` POSTs a new key
  and updates the cache; the previous key stops working
- Don't report `visits: 0` / `engagement: 0` for a synthetic aggregate domain
  (any domain containing a colon, e.g. `aem.live:all`). Those checkpoints are
  not collected; the CLI reports `n/a`. The AEM website itself is `www.aem.live`
- Don't forget `utils.addCalculatedProps(b)` before loading bundles into `DataChunks` —
  visits and core-web-vitals series silently return zero/undefined without it
