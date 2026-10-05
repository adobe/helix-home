// Oversight — RUM / Operational Telemetry query tool
// Queries AEM Edge Delivery Services RUM data via bundles.aem.page

const API_ENDPOINT = 'https://bundles.aem.page';

// Runtime bridges: in the SLICC .jsh runtime former bare globals are exposed
// via require('sliccy:<name>'). Credentials (the RUM admin key and cached
// domain keys) are persisted through the per-skill config bridge (skill.config),
// which writes a gitignored `.config` next to the skill — no raw fs to $HOME.
const skill = require('sliccy:skill');

// --- Config ---

async function loadConfig() {
  // skill.config() reads the parsed JSON from the skill's gitignored `.config`
  // (returns null when it does not exist yet). Must await before the fallback:
  // the raw call returns a Promise, which is always truthy.
  return (await skill.config()) || { adminKey: '', domainKeys: {} };
}

async function saveConfig(config) {
  // skill.config(updates) shallow-merges and persists to the skill's
  // gitignored `.config`, returning the merged object.
  return await skill.config(config);
}

async function ensureAdminKey() {
  const config = await loadConfig();
  if (!config.adminKey) {
    console.error('No admin key configured. Run `oversight login --key=<KEY>` first.');
    process.exit(1);
  }
  return config;
}

// --- Domain key management ---

function fingerprintKey(key) {
  return String(key || '').substring(0, 8) + '...';
}

function redactSecrets(text) {
  return String(text || '')
    .replace(/"domainkey"\s*:\s*"[^"]*"/gi, '"domainkey":"[redacted]"')
    .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]');
}

function formatKeyResult(domain, key, show, extra) {
  const result = {
    domain,
    domainkey: show ? key : fingerprintKey(key),
    cached: true,
  };
  if (extra) {
    for (const k of Object.keys(extra)) result[k] = extra[k];
  }
  if (!show) {
    result.note = 'Full key cached in the skill config (gitignored). Pass --show to print it.';
  }
  return result;
}

async function mintDomainKey(domain, opts) {
  const force = opts && opts.force;
  const config = await ensureAdminKey();

  if (!force) {
    // First try GET to retrieve an existing key
    const getResp = await fetch(API_ENDPOINT + '/domainkey/' + domain, {
      method: 'GET',
      headers: { authorization: 'Bearer ' + config.adminKey },
    });

    if (getResp.ok) {
      try {
        const data = await getResp.json();
        if (data.domainkey) {
          config.domainKeys = config.domainKeys || {};
          config.domainKeys[domain] = data.domainkey;
          await saveConfig(config);
          return data.domainkey;
        }
      } catch (e) { /* fall through to POST */ }
    }
  }

  // POST to mint a new key, or to rotate (force) by replacing the existing one
  const postResp = await fetch(API_ENDPOINT + '/domainkey/' + domain, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + config.adminKey },
  });

  if (!postResp.ok) {
    const text = await postResp.text();
    console.error('Failed to mint domain key (' + postResp.status + '): ' + redactSecrets(text));
    process.exit(1);
  }

  const data = await postResp.json();
  config.domainKeys = config.domainKeys || {};
  config.domainKeys[domain] = data.domainkey;
  await saveConfig(config);
  return data.domainkey;
}

async function getDomainKey(domain) {
  const config = await loadConfig();
  if (config.domainKeys && config.domainKeys[domain]) {
    return config.domainKeys[domain];
  }
  // Auto-mint if we have an admin key
  if (config.adminKey) {
    return mintDomainKey(domain);
  }
  console.error('No domain key for ' + domain + '. Run `oversight mint ' + domain + '` first.');
  process.exit(1);
}

// --- Data fetching ---

class BundleFetchError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

// Fetches one bundler slot (YYYY/MM, YYYY/MM/DD or YYYY/MM/DD/HH). Throws a
// BundleFetchError instead of exiting, so a range fetch can count the failed
// slot and report it rather than dying (or silently treating it as zero).
async function fetchBundles(domain, datePath) {
  const domainKey = await getDomainKey(domain);
  const url = API_ENDPOINT + '/bundles/' + domain + '/' + datePath + '?domainkey=' + encodeURIComponent(domainKey);
  const resp = await fetch(url);

  if (resp.status === 401) {
    // Key might be stale; try re-minting
    const config = await loadConfig();
    delete config.domainKeys[domain];
    await saveConfig(config);
    const newKey = await mintDomainKey(domain);
    const retryUrl = API_ENDPOINT + '/bundles/' + domain + '/' + datePath + '?domainkey=' + encodeURIComponent(newKey);
    const retryResp = await fetch(retryUrl);
    if (!retryResp.ok) {
      throw new BundleFetchError('HTTP ' + retryResp.status + ' after re-mint', retryResp.status);
    }
    return retryResp.json();
  }

  if (!resp.ok) {
    throw new BundleFetchError('HTTP ' + resp.status, resp.status);
  }
  return resp.json();
}

// Fetches every slot of the plan in parallel. Failed slots are collected in
// `failedSlots` (never silently dropped as zero); bundles outside the plan's
// window are trimmed away.
async function fetchRange(domain, range) {
  let plan;
  try {
    plan = planRange(range, new Date());
  } catch (err) {
    console.error(err.message);
    process.exit(1);
  }
  // Resolve (and, with an admin key, mint and cache) the domain key once,
  // before the fan-out, so the parallel slot fetches all hit the cache instead
  // of racing one mint per slot.
  await getDomainKey(domain);
  const results = await Promise.all(plan.slots.map((slot) => fetchBundles(domain, slot)
    .then((data) => ({ slot, data }))
    .catch((err) => ({ slot, error: err && err.message ? err.message : String(err) }))));

  const failedSlots = [];
  const fetched = [];
  for (const r of results) {
    if (r.error) {
      failedSlots.push({ slot: r.slot, error: r.error });
    } else if (r.data && Array.isArray(r.data.rumBundles)) {
      fetched.push(...r.data.rumBundles);
    }
  }

  if (failedSlots.length) {
    console.error('Warning: ' + failedSlots.length + ' of ' + plan.slots.length +
      ' bundle slots failed; totals below exclude them: ' +
      failedSlots.map((f) => f.slot + ' (' + f.error + ')').join(', '));
  }
  if (plan.slots.length && failedSlots.length === plan.slots.length) {
    console.error('All ' + plan.slots.length + ' bundle slots failed for ' + domain + '; no data to report.');
    process.exit(1);
  }

  return {
    bundles: trimToWindow(fetched, plan.from, plan.to),
    failedSlots,
    window: {
      from: plan.from ? plan.from.toISOString() : null,
      to: plan.to ? plan.to.toISOString() : null,
      slots: plan.slots.length,
    },
  };
}

// --- rum-distiller ---

// The metric rules (what counts as a page view, a visit, engagement, a
// bounce; how CWV values are taken per bundle) come from @adobe/rum-distiller,
// the library behind the OpTel Explorer, so the CLI cannot drift from the UI.
// Pinned: bump deliberately and re-check the numbers.
const DISTILLER_URL = 'https://esm.sh/@adobe/rum-distiller@1.23.1';

// A literal `import()` in a .jsh is lowered to require() by the SLICC realm,
// which rejects https specifiers ("Cannot find module ... run: ipk install
// https:"). A function built from a string keeps the worker's native import().
const nativeImport = new Function('specifier', 'return import(specifier)');

async function loadDistiller() {
  try {
    return await nativeImport(DISTILLER_URL);
  } catch (err) {
    console.error('Failed to load ' + DISTILLER_URL + ': ' + (err && err.message ? err.message : err));
    process.exit(1);
  }
}

// --- Analysis helpers ---
// Pure functions below: no fetch, no config, no process access. `rd` is the
// @adobe/rum-distiller module namespace ({ DataChunks, series, facets, utils }).

const SERIES_NAMES = ['pageViews', 'visits', 'bounces', 'engagement', 'lcp', 'cls', 'inp'];

function pad2(n) {
  return String(n).padStart(2, '0');
}

// The bundler partitions by UTC. Never use the local-time getters here.
function utcDayPath(d) {
  return d.getUTCFullYear() + '/' + pad2(d.getUTCMonth() + 1) + '/' + pad2(d.getUTCDate());
}

function utcMonthPath(d) {
  return d.getUTCFullYear() + '/' + pad2(d.getUTCMonth() + 1);
}

// Which bundler slots to fetch for a range, and the [from, to] window to trim
// the bundles to (null = keep everything in the fetched slots).
//   day   — rolling last 24 h: yesterday's + today's UTC daily files, trimmed
//   week  — rolling last 7 x 24 h: 8 UTC daily files, trimmed
//   month — today + the previous 30 UTC days (31 daily files, untrimmed)
//   year  — this + the previous 11 UTC months (12 monthly files, untrimmed)
function planRange(range, now) {
  const DAY_MS = 24 * 3600 * 1000;
  const dayPaths = (n) => {
    const paths = [];
    for (let i = 0; i < n; i++) paths.push(utcDayPath(new Date(now.getTime() - i * DAY_MS)));
    return paths;
  };
  if (range === 'day' || range === 'week') {
    const days = range === 'day' ? 1 : 7;
    return {
      slots: dayPaths(days + 1),
      from: new Date(now.getTime() - days * DAY_MS),
      to: now,
    };
  }
  if (range === 'month') {
    return { slots: dayPaths(31), from: null, to: null };
  }
  if (range === 'year') {
    const slots = [];
    for (let i = 0; i < 12; i++) {
      slots.push(utcMonthPath(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1))));
    }
    return { slots, from: null, to: null };
  }
  throw new Error('Unknown --range=' + range + ' (expected day, week, month or year)');
}

function trimToWindow(bundles, from, to) {
  if (!from && !to) return bundles;
  return bundles.filter((b) => {
    const t = new Date(b.time || b.timeSlot).getTime();
    if (Number.isNaN(t)) return false;
    return (!from || t > from.getTime()) && (!to || t <= to.getTime());
  });
}

function buildDataChunks(rd, bundles) {
  // addCalculatedProps sets bundle.visit and the per-bundle CWV values
  // (cwvLCP/cwvCLS = max over the bundle's events). Without it, visits and
  // vitals come back empty.
  for (const b of bundles) rd.utils.addCalculatedProps(b);
  const dc = new rd.DataChunks();
  dc.load([{ date: 'range', rumBundles: bundles }]);
  for (const name of SERIES_NAMES) dc.addSeries(name, rd.series[name]);
  return dc;
}

function p75(aggregate) {
  if (!aggregate || aggregate.count === 0) return null;
  const v = aggregate.percentile(75);
  return v === undefined ? null : v;
}

function rate(rd, part, whole) {
  if (!(whole > 0)) return 0;
  // Same helper (and cap at 100) the OpTel Explorer uses for its rates.
  return Math.round(rd.utils.computeConversionRate(part, whole) * 10) / 10;
}

function computeMetrics(rd, bundles) {
  const t = buildDataChunks(rd, bundles).totals;
  const pageViews = t.pageViews.sum;
  const visits = t.visits.sum;
  const bounces = t.bounces.sum;
  const engagement = t.engagement.sum;
  return {
    pageViews,
    visits,
    bounces,
    engagement,
    // OpTel Explorer definitions: engaged page views / page views, and
    // bounced visits / visits.
    engagementRate: rate(rd, engagement, pageViews),
    bounceRate: rate(rd, bounces, visits),
    vitals: {
      lcp: p75(t.lcp),
      cls: p75(t.cls),
      inp: p75(t.inp),
    },
    samples: {
      lcp: t.lcp.count,
      cls: t.cls.count,
      inp: t.inp.count,
    },
  };
}

function computeTopPages(rd, bundles, limit) {
  const dc = buildDataChunks(rd, bundles);
  // Group on the bundle URL as delivered (the bundler already normalizes ids);
  // only the counting rule (series.pageViews) comes from distiller.
  dc.addFacet('url', (b) => b.url || '(unknown)');
  return dc.facets.url
    .map((f) => ({ url: f.value, pageViews: f.metrics.pageViews.sum }))
    .filter((p) => p.pageViews > 0)
    .sort((a, b) => b.pageViews - a.pageViews)
    .slice(0, limit);
}

function computeTimeSeries(rd, bundles) {
  const dc = buildDataChunks(rd, bundles);
  dc.group((b) => b.timeSlot || '(unknown)');
  const aggregates = dc.aggregates;
  return Object.keys(aggregates)
    .sort((a, b) => a.localeCompare(b))
    .map((time) => ({ time, pageViews: aggregates[time].pageViews.sum }));
}

function isSyntheticDomain(domain) {
  return typeof domain === 'string' && domain.includes(':');
}

function formatStatusResult(domain, range, fetched, metrics) {
  const result = {
    domain,
    range,
    bundleCount: fetched.bundles.length,
    pageViews: metrics.pageViews,
    visits: metrics.visits,
    bounces: metrics.bounces,
    engagement: metrics.engagement,
    engagementRate: metrics.engagementRate + '%',
    bounceRate: metrics.bounceRate + '%',
    vitals: {
      lcp: metrics.vitals.lcp !== null ? (metrics.vitals.lcp / 1000).toFixed(2) + 's' : 'N/A',
      cls: metrics.vitals.cls !== null ? metrics.vitals.cls.toFixed(3) : 'N/A',
      inp: metrics.vitals.inp !== null ? (metrics.vitals.inp / 1000).toFixed(2) + 's' : 'N/A',
    },
    window: fetched.window,
    failedSlots: fetched.failedSlots,
  };

  if (isSyntheticDomain(domain)) {
    result.synthetic = true;
    result.visits = null;
    result.bounces = null;
    result.engagement = null;
    result.engagementRate = 'n/a (synthetic aggregate domain)';
    result.bounceRate = 'n/a (synthetic aggregate domain)';
    result.note = 'Engagement, visits and bounces are not computable for a synthetic aggregate domain — most checkpoints are not collected.';
  }

  return result;
}

// --- Argument parsing ---

function parseArgs(args) {
  const opts = {
    range: 'month',
    date: null,
    limit: 20,
    key: null,
    show: false,
  };
  const positional = [];

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--range=')) opts.range = arg.split('=')[1];
    else if (arg.startsWith('--date=')) opts.date = arg.split('=')[1];
    else if (arg.startsWith('--limit=')) opts.limit = parseInt(arg.split('=')[1]);
    else if (arg.startsWith('--key=')) opts.key = arg.split('=').slice(1).join('=');
    else if (arg === '--show' || arg === '--print-key') opts.show = true;
    else positional.push(arg);
  }

  return { opts, positional };
}

// --- Commands ---

async function cmdLogin(args) {
  const { opts } = parseArgs(args);
  if (!opts.key) {
    console.error('Usage: oversight login --key=<ADMIN_KEY>');
    console.error('');
    console.error('The admin key is a RUM bundler token that can mint domain-specific keys.');
    console.error('It is sent as a Bearer token to the /domainkey/ endpoint.');
    process.exit(1);
  }

  // Validate by attempting to reach the API
  const resp = await fetch(API_ENDPOINT + '/domainkey/__probe__', {
    method: 'GET',
    headers: { authorization: 'Bearer ' + opts.key },
  });
  // 404 is expected for a non-existent domain; 401/403 means bad key
  if (resp.status === 401 || resp.status === 403) {
    console.error('Admin key rejected by the bundler API (HTTP ' + resp.status + ').');
    process.exit(1);
  }

  const config = await loadConfig();
  config.adminKey = opts.key;
  config.domainKeys = config.domainKeys || {};
  await saveConfig(config);
  console.log('Admin key saved to the oversight skill config (gitignored).');
  console.log('');
  console.log('Next steps:');
  console.log('  oversight mint <domain>     — mint or retrieve a domain key');
  console.log('  oversight rotate <domain>   — replace a compromised domain key');
  console.log('  oversight status <domain>   — quick traffic overview');
}

async function cmdMint(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight mint <domain> [--show]');
    process.exit(1);
  }

  const key = await mintDomainKey(domain);
  console.log(JSON.stringify(formatKeyResult(domain, key, opts.show), null, 2));
}

async function cmdRotate(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight rotate <domain> [--show]');
    process.exit(1);
  }

  const key = await mintDomainKey(domain, { force: true });
  console.log(JSON.stringify(formatKeyResult(domain, key, opts.show, { rotated: true }), null, 2));
}

async function cmdKeys() {
  const config = await loadConfig();
  const keys = config.domainKeys || {};
  if (Object.keys(keys).length === 0) {
    console.log('No domain keys cached. Run `oversight mint <domain>` to mint one.');
    return;
  }
  const result = Object.entries(keys).map(([domain, key]) => ({
    domain,
    domainkey: fingerprintKey(key),
  }));
  console.log(JSON.stringify(result, null, 2));
}

async function cmdStatus(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight status <domain> [--range=month]');
    process.exit(1);
  }

  const rd = await loadDistiller();
  const fetched = await fetchRange(domain, opts.range);
  const metrics = computeMetrics(rd, fetched.bundles);
  const result = formatStatusResult(domain, opts.range, fetched, metrics);

  console.log(JSON.stringify(result, null, 2));
}

async function cmdPageviews(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight pageviews <domain> [--range=month]');
    process.exit(1);
  }

  const rd = await loadDistiller();
  const fetched = await fetchRange(domain, opts.range);
  const timeSeries = computeTimeSeries(rd, fetched.bundles);

  const total = timeSeries.reduce((sum, p) => sum + p.pageViews, 0);
  const result = {
    domain,
    range: opts.range,
    totalPageViews: total,
    timeSeries,
    window: fetched.window,
    failedSlots: fetched.failedSlots,
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdVitals(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight vitals <domain> [--range=month]');
    process.exit(1);
  }

  const rd = await loadDistiller();
  const fetched = await fetchRange(domain, opts.range);
  const bundles = fetched.bundles;
  const metrics = computeMetrics(rd, bundles);

  function scoreCWV(value, metric) {
    if (value === null) return 'N/A';
    if (metric === 'lcp') return value <= 2500 ? 'good' : value <= 4000 ? 'needs-improvement' : 'poor';
    if (metric === 'cls') return value <= 0.1 ? 'good' : value <= 0.25 ? 'needs-improvement' : 'poor';
    if (metric === 'inp') return value <= 200 ? 'good' : value <= 500 ? 'needs-improvement' : 'poor';
    return 'unknown';
  }

  const result = {
    domain,
    range: opts.range,
    sampleSize: bundles.length,
    lcp: {
      value: metrics.vitals.lcp !== null ? (metrics.vitals.lcp / 1000).toFixed(2) + 's' : 'N/A',
      raw_ms: metrics.vitals.lcp,
      score: scoreCWV(metrics.vitals.lcp, 'lcp'),
      samples: metrics.samples.lcp,
    },
    cls: {
      value: metrics.vitals.cls !== null ? metrics.vitals.cls.toFixed(3) : 'N/A',
      raw: metrics.vitals.cls,
      score: scoreCWV(metrics.vitals.cls, 'cls'),
      samples: metrics.samples.cls,
    },
    inp: {
      value: metrics.vitals.inp !== null ? (metrics.vitals.inp / 1000).toFixed(2) + 's' : 'N/A',
      raw_ms: metrics.vitals.inp,
      score: scoreCWV(metrics.vitals.inp, 'inp'),
      samples: metrics.samples.inp,
    },
    window: fetched.window,
    failedSlots: fetched.failedSlots,
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdTopPages(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight top-pages <domain> [--range=month] [--limit=20]');
    process.exit(1);
  }

  const rd = await loadDistiller();
  const fetched = await fetchRange(domain, opts.range);
  const topPages = computeTopPages(rd, fetched.bundles, opts.limit);

  const result = {
    domain,
    range: opts.range,
    pages: topPages,
    window: fetched.window,
    failedSlots: fetched.failedSlots,
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdBundles(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight bundles <domain> [--date=YYYY-MM-DD]');
    process.exit(1);
  }

  // YYYY-MM-DD parses as UTC midnight; read it back with the UTC getters
  // (local getters map it to the previous day west of UTC).
  const date = opts.date ? new Date(opts.date) : new Date();
  if (Number.isNaN(date.getTime())) {
    console.error('Invalid --date=' + opts.date + ' (expected YYYY-MM-DD)');
    process.exit(1);
  }
  const datePath = utcDayPath(date);

  let data;
  try {
    data = await fetchBundles(domain, datePath);
  } catch (err) {
    console.error('Bundle fetch failed for ' + datePath + ' (' + err.message + ')');
    process.exit(1);
  }

  const result = {
    domain,
    date: datePath,
    bundleCount: data.rumBundles ? data.rumBundles.length : 0,
    bundles: data.rumBundles || [],
  };

  console.log(JSON.stringify(result, null, 2));
}

function showHelp() {
  console.log('oversight — RUM / Operational Telemetry query tool\n');
  console.log('Setup:');
  console.log('  login --key=<KEY>            Store admin key for domain key minting\n');
  console.log('Domain key management:');
  console.log('  mint <domain>                Retrieve or create a domain key');
  console.log('  rotate <domain>              Replace a domain key (POST, skips GET)');
  console.log('  keys                         List cached domain keys (fingerprints)\n');
  console.log('Queries:');
  console.log('  status <domain>              Quick overview: page views, visits, vitals');
  console.log('  pageviews <domain>           Page view time series');
  console.log('  vitals <domain>              Core Web Vitals (LCP, CLS, INP) at p75');
  console.log('  top-pages <domain>           Top URLs by page views');
  console.log('  bundles <domain>             Fetch raw bundle data for a date\n');
  console.log('Flags:');
  console.log('  --range=RANGE                day (last 24 h), week (last 7 d), month (31 UTC days),');
  console.log('                               year (12 UTC months); default: month');
  console.log('  --date=YYYY-MM-DD            Specific UTC date for bundle fetch');
  console.log('  --limit=N                    Number of results (default: 20)');
  console.log('  --show                       Print the full domain key (mint/rotate only)\n');
  console.log('Auth model:');
  console.log('  Admin key → POST /domainkey/<domain> → mints a domain key (201)');
  console.log('  Domain key → ?domainkey=<key> on bundle fetches');
  console.log('  GET /domainkey/<domain> retrieves an existing key (does not create)');
  console.log('  rotate POSTs a new key even when GET would succeed\n');
  console.log('Metrics are computed with @adobe/rum-distiller (same rules as the OpTel Explorer).\n');
  console.log('Examples:');
  console.log('  oversight login --key=YOUR-ADMIN-KEY');
  console.log('  oversight mint www.example.com');
  console.log('  oversight rotate www.example.com');
  console.log('  oversight status www.example.com');
  console.log('  oversight vitals www.example.com --range=week');
  console.log('  oversight top-pages www.example.com --limit=10');
}

// --- Main ---

const rawArgs = process.argv.slice(2);
const cmd = rawArgs[0];
const args = rawArgs.slice(1);

if (!cmd || cmd === 'help' || cmd === '--help') {
  showHelp();
  process.exit(cmd ? 0 : 1);
}

switch (cmd) {
  case 'login':
    await cmdLogin(args);
    break;
  case 'mint':
    await cmdMint(args);
    break;
  case 'rotate':
    await cmdRotate(args);
    break;
  case 'keys':
    await cmdKeys();
    break;
  case 'status':
    await cmdStatus(args);
    break;
  case 'pageviews':
    await cmdPageviews(args);
    break;
  case 'vitals':
    await cmdVitals(args);
    break;
  case 'top-pages':
    await cmdTopPages(args);
    break;
  case 'bundles':
    await cmdBundles(args);
    break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
