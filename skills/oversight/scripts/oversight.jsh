// Oversight — RUM / Operational Telemetry query tool
// Queries AEM Edge Delivery Services RUM data via bundles.aem.page

const CONFIG_DIR = (process.env.HOME || process.env.USERPROFILE || '/root') + '/.config/oversight';
const CONFIG_FILE = CONFIG_DIR + '/config.json';
const API_ENDPOINT = 'https://bundles.aem.page';

// --- Config ---

let _config = null;

async function loadConfig() {
  if (_config) return _config;
  try {
    const raw = await fs.readFile(CONFIG_FILE, 'utf8');
    _config = JSON.parse(raw);
    return _config;
  } catch (e) {
    return { adminKey: '', domainKeys: {} };
  }
}

async function saveConfig(config) {
  _config = config;
  await fs.mkdir(CONFIG_DIR, { recursive: true }).catch(function() {});
  await fs.writeFile(CONFIG_FILE, JSON.stringify(config, null, 2));
  await fs.chmod(CONFIG_FILE, 0o600).catch(function() {});
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

async function mintDomainKey(domain) {
  const config = await ensureAdminKey();

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

  // If GET didn't return a key, POST to mint a new one
  const postResp = await fetch(API_ENDPOINT + '/domainkey/' + domain, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + config.adminKey },
  });

  if (!postResp.ok) {
    const text = await postResp.text();
    console.error('Failed to mint domain key (' + postResp.status + '): ' + text);
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
      console.error('Bundle fetch failed after re-mint (' + retryResp.status + ')');
      process.exit(1);
    }
    return retryResp.json();
  }

  if (!resp.ok) {
    console.error('Bundle fetch failed (' + resp.status + ')');
    process.exit(1);
  }
  return resp.json();
}

async function fetchRange(domain, range) {
  const now = new Date();
  const promises = [];

  if (range === 'day' || range === 'week') {
    const days = range === 'day' ? 1 : 7;
    for (let i = 0; i < days; i++) {
      const d = new Date(now);
      d.setDate(d.getDate() - i);
      const path = d.getFullYear() + '/' +
        String(d.getMonth() + 1).padStart(2, '0') + '/' +
        String(d.getDate()).padStart(2, '0');
      promises.push(fetchBundles(domain, path).catch(() => ({ rumBundles: [] })));
    }
  } else if (range === 'month') {
    for (let i = 0; i < 31; i++) {
      const d = new Date(now);
      d.setDate(d.getDate() - i);
      const path = d.getFullYear() + '/' +
        String(d.getMonth() + 1).padStart(2, '0') + '/' +
        String(d.getDate()).padStart(2, '0');
      promises.push(fetchBundles(domain, path).catch(() => ({ rumBundles: [] })));
    }
  } else if (range === 'year') {
    for (let i = 0; i < 12; i++) {
      const d = new Date(now);
      d.setUTCMonth(d.getUTCMonth() - i, 1);
      const path = d.getFullYear() + '/' +
        String(d.getMonth() + 1).padStart(2, '0');
      promises.push(fetchBundles(domain, path).catch(() => ({ rumBundles: [] })));
    }
  }

  const results = await Promise.all(promises);
  const allBundles = [];
  for (const r of results) {
    if (r && r.rumBundles) {
      allBundles.push(...r.rumBundles);
    }
  }
  return allBundles;
}

// --- Analysis helpers ---

function computeMetrics(bundles) {
  let pageViews = 0;
  let visits = 0;
  let engagement = 0;
  const lcpValues = [];
  const clsValues = [];
  const inpValues = [];

  for (const bundle of bundles) {
    const w = bundle.weight || 1;
    pageViews += w;

    const events = bundle.events || [];
    const hasEnter = events.some(e => e.checkpoint === 'enter');
    const hasClick = events.some(e => e.checkpoint === 'click');

    if (hasEnter) visits += w;
    if (hasClick) engagement += w;

    for (const evt of events) {
      if (evt.checkpoint === 'cwv-lcp' && typeof evt.value === 'number') {
        lcpValues.push(evt.value);
      }
      if (evt.checkpoint === 'cwv-cls' && typeof evt.value === 'number') {
        clsValues.push(evt.value);
      }
      if (evt.checkpoint === 'cwv-inp' && typeof evt.value === 'number') {
        inpValues.push(evt.value);
      }
    }
  }

  function p75(arr) {
    if (arr.length === 0) return null;
    arr.sort((a, b) => a - b);
    const idx = Math.floor(arr.length * 0.75);
    return arr[idx];
  }

  return {
    pageViews,
    visits,
    engagement,
    engagementRate: visits > 0 ? Math.round(engagement * 1000 / visits) / 10 : 0,
    vitals: {
      lcp: p75(lcpValues),
      cls: p75(clsValues),
      inp: p75(inpValues),
    },
  };
}

function computeTopPages(bundles, limit) {
  const pages = {};
  for (const bundle of bundles) {
    const url = bundle.url || '(unknown)';
    const w = bundle.weight || 1;
    pages[url] = (pages[url] || 0) + w;
  }
  return Object.entries(pages)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([url, views]) => ({ url, pageViews: views }));
}

function computeTimeSeries(bundles) {
  const slots = {};
  for (const bundle of bundles) {
    const slot = bundle.timeSlot || '(unknown)';
    const w = bundle.weight || 1;
    slots[slot] = (slots[slot] || 0) + w;
  }
  return Object.entries(slots)
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([time, views]) => ({ time, pageViews: views }));
}

// --- Argument parsing ---

function parseArgs(args) {
  const opts = {
    range: 'month',
    date: null,
    limit: 20,
    key: null,
  };
  const positional = [];

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--range=')) opts.range = arg.split('=')[1];
    else if (arg.startsWith('--date=')) opts.date = arg.split('=')[1];
    else if (arg.startsWith('--limit=')) opts.limit = parseInt(arg.split('=')[1]);
    else if (arg.startsWith('--key=')) opts.key = arg.split('=').slice(1).join('=');
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
  console.log('Admin key saved to ' + CONFIG_FILE);
  console.log('');
  console.log('Next steps:');
  console.log('  oversight mint <domain>     — mint a domain key');
  console.log('  oversight status <domain>   — quick traffic overview');
}

async function cmdMint(args) {
  const { positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight mint <domain>');
    process.exit(1);
  }

  const key = await mintDomainKey(domain);
  console.log(JSON.stringify({ domain, domainkey: key }, null, 2));
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
    domainkey: key.substring(0, 8) + '...',
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

  const bundles = await fetchRange(domain, opts.range);
  const metrics = computeMetrics(bundles);

  const result = {
    domain,
    range: opts.range,
    bundleCount: bundles.length,
    pageViews: metrics.pageViews,
    visits: metrics.visits,
    engagement: metrics.engagement,
    engagementRate: metrics.engagementRate + '%',
    vitals: {
      lcp: metrics.vitals.lcp !== null ? (metrics.vitals.lcp / 1000).toFixed(2) + 's' : 'N/A',
      cls: metrics.vitals.cls !== null ? metrics.vitals.cls.toFixed(3) : 'N/A',
      inp: metrics.vitals.inp !== null ? (metrics.vitals.inp / 1000).toFixed(2) + 's' : 'N/A',
    },
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdPageviews(args) {
  const { opts, positional } = parseArgs(args);
  const domain = positional[0];
  if (!domain) {
    console.error('Usage: oversight pageviews <domain> [--range=month]');
    process.exit(1);
  }

  const bundles = await fetchRange(domain, opts.range);
  const timeSeries = computeTimeSeries(bundles);

  const total = timeSeries.reduce((sum, p) => sum + p.pageViews, 0);
  const result = {
    domain,
    range: opts.range,
    totalPageViews: total,
    timeSeries,
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

  const bundles = await fetchRange(domain, opts.range);
  const metrics = computeMetrics(bundles);

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
    },
    cls: {
      value: metrics.vitals.cls !== null ? metrics.vitals.cls.toFixed(3) : 'N/A',
      raw: metrics.vitals.cls,
      score: scoreCWV(metrics.vitals.cls, 'cls'),
    },
    inp: {
      value: metrics.vitals.inp !== null ? (metrics.vitals.inp / 1000).toFixed(2) + 's' : 'N/A',
      raw_ms: metrics.vitals.inp,
      score: scoreCWV(metrics.vitals.inp, 'inp'),
    },
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

  const bundles = await fetchRange(domain, opts.range);
  const topPages = computeTopPages(bundles, opts.limit);

  const result = {
    domain,
    range: opts.range,
    pages: topPages,
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

  const date = opts.date ? new Date(opts.date) : new Date();
  const datePath = date.getFullYear() + '/' +
    String(date.getMonth() + 1).padStart(2, '0') + '/' +
    String(date.getDate()).padStart(2, '0');

  const data = await fetchBundles(domain, datePath);

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
  console.log('  mint <domain>                Mint or retrieve a domain key');
  console.log('  keys                         List cached domain keys\n');
  console.log('Queries:');
  console.log('  status <domain>              Quick overview: page views, visits, vitals');
  console.log('  pageviews <domain>           Page view time series');
  console.log('  vitals <domain>              Core Web Vitals (LCP, CLS, INP) at p75');
  console.log('  top-pages <domain>           Top URLs by page views');
  console.log('  bundles <domain>             Fetch raw bundle data for a date\n');
  console.log('Flags:');
  console.log('  --range=RANGE                day, week, month, year (default: month)');
  console.log('  --date=YYYY-MM-DD            Specific date for bundle fetch');
  console.log('  --limit=N                    Number of results (default: 20)\n');
  console.log('Auth model:');
  console.log('  Admin key → POST /domainkey/<domain> → mints a domain key (201)');
  console.log('  Domain key → ?domainkey=<key> on bundle fetches');
  console.log('  GET /domainkey/<domain> retrieves an existing key (does not create)\n');
  console.log('Examples:');
  console.log('  oversight login --key=YOUR-ADMIN-KEY');
  console.log('  oversight mint www.example.com');
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
