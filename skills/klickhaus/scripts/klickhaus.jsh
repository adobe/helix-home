// Klickhaus — CDN incident investigation tool
// Queries AEM Edge Delivery Services CDN logs in ClickHouse

const CONFIG_DIR = (process.env.HOME || process.env.USERPROFILE || '/root') + '/.config/klickhaus';
const CONFIG_FILE = CONFIG_DIR + '/config.json';
const CLICKHOUSE_URL = 'https://s2p5b8wmt5.eastus2.azure.clickhouse.cloud/';
const DATABASE = 'helix_logs_production';

const TIME_RANGES = {
  '15m': { interval: 'INTERVAL 15 MINUTE', bucket: "toStartOfInterval(timestamp, INTERVAL 1 MINUTE)", step: 'INTERVAL 1 MINUTE' },
  '1h':  { interval: 'INTERVAL 1 HOUR', bucket: "toStartOfInterval(timestamp, INTERVAL 2 MINUTE)", step: 'INTERVAL 2 MINUTE' },
  '12h': { interval: 'INTERVAL 12 HOUR', bucket: "toStartOfInterval(timestamp, INTERVAL 15 MINUTE)", step: 'INTERVAL 15 MINUTE' },
  '24h': { interval: 'INTERVAL 24 HOUR', bucket: "toStartOfInterval(timestamp, INTERVAL 30 MINUTE)", step: 'INTERVAL 30 MINUTE' },
  '3d':  { interval: 'INTERVAL 3 DAY', bucket: "toStartOfInterval(timestamp, INTERVAL 2 HOUR)", step: 'INTERVAL 2 HOUR' },
  '7d':  { interval: 'INTERVAL 7 DAY', bucket: "toStartOfInterval(timestamp, INTERVAL 6 HOUR)", step: 'INTERVAL 6 HOUR' },
};

const DIMENSIONS = {
  host:           '`request.host`',
  status:         'toString(`response.status`)',
  path:           '`request.url`',
  content_type:   '`response.headers.content_type`',
  cache:          'upper(`cdn.cache_status`)',
  method:         '`request.method`',
  request_type:   '`helix.request_type`',
  backend_type:   '`helix.backend_type`',
  forwarded_host: '`request.headers.x_forwarded_host`',
  referer:        '`request.headers.referer`',
  user_agent:     '`request.headers.user_agent`',
  datacenter:     '`cdn.datacenter`',
  asn:            'toString(`client.asn`)',
  ip:             '`client.ip`',
};

// --- Config ---

let _config = null;

async function loadConfig() {
  if (_config) return _config;
  try {
    const raw = await fs.readFile(CONFIG_FILE, 'utf8');
    _config = JSON.parse(raw);
    return _config;
  } catch (e) {
    return null;
  }
}

async function saveConfig(config) {
  _config = config;
  await fs.mkdir(CONFIG_DIR, { recursive: true }).catch(function() {});
  await fs.writeFile(CONFIG_FILE, JSON.stringify(config, null, 2));
  await fs.chmod(CONFIG_FILE, 0o600).catch(function() {});
}

async function ensureAuth() {
  const config = await loadConfig();
  if (!config || !config.user || !config.password) {
    console.error('Not logged in. Run `klickhaus login` first.');
    process.exit(1);
  }
  return config;
}

// --- Query engine ---

async function runQuery(sql) {
  const config = await ensureAuth();
  const url = CLICKHOUSE_URL + '?database=' + DATABASE;
  const auth = btoa(config.user + ':' + config.password);

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Authorization': 'Basic ' + auth,
    },
    body: sql + ' FORMAT JSON',
  });

  if (!response.ok) {
    const text = await response.text();
    if (response.status === 401 || text.includes('Authentication failed') || text.includes('REQUIRED_PASSWORD')) {
      console.error('Authentication failed. Check credentials with `klickhaus login`.');
      process.exit(1);
    }
    console.error('Query error (' + response.status + '): ' + text.split('\n')[0]);
    process.exit(1);
  }

  return response.json();
}

// --- Argument parsing ---

function parseArgs(args) {
  const opts = {
    table: 'delivery',
    range: '1h',
    host: null,
    status: null,
    limit: 20,
  };
  const positional = [];

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--table=')) opts.table = arg.split('=')[1];
    else if (arg.startsWith('--range=')) opts.range = arg.split('=')[1];
    else if (arg.startsWith('--host=')) opts.host = arg.split('=')[1];
    else if (arg.startsWith('--status=')) opts.status = arg.split('=')[1];
    else if (arg.startsWith('--limit=')) opts.limit = parseInt(arg.split('=')[1]);
    else positional.push(arg);
  }

  if (!TIME_RANGES[opts.range]) {
    console.error('Invalid range: ' + opts.range + '. Valid: ' + Object.keys(TIME_RANGES).join(', '));
    process.exit(1);
  }

  return { opts, positional };
}

function buildWhereClause(opts) {
  const tr = TIME_RANGES[opts.range];
  let where = 'timestamp >= now() - ' + tr.interval;
  if (opts.host) {
    where += " AND `request.host` = '" + opts.host.replace(/'/g, "\\'") + "'";
  }
  if (opts.status) {
    if (opts.status === '5xx') {
      where += ' AND `response.status` >= 500';
    } else if (opts.status === '4xx') {
      where += ' AND `response.status` >= 400 AND `response.status` < 500';
    } else {
      where += ' AND `response.status` = ' + parseInt(opts.status);
    }
  }
  return where;
}

// --- Commands ---

async function cmdLogin(args) {
  // Parse --user= and --password= from args
  let user = null;
  let password = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith('--user=')) user = args[i].split('=').slice(1).join('=');
    else if (args[i].startsWith('--password=')) password = args[i].split('=').slice(1).join('=');
    else if (!user) user = args[i];
    else if (!password) password = args[i];
  }

  if (!user || !password) {
    console.error('Usage: klickhaus login --user=USERNAME --password=PASSWORD');
    console.error('   or: klickhaus login USERNAME PASSWORD');
    console.error('');
    console.error('URL: ' + CLICKHOUSE_URL);
    console.error('Database: ' + DATABASE);
    process.exit(1);
  }

  // Test the connection
  const auth = btoa(user + ':' + password);
  const testUrl = CLICKHOUSE_URL + '?database=' + DATABASE;
  const resp = await fetch(testUrl, {
    method: 'POST',
    headers: { 'Authorization': 'Basic ' + auth },
    body: 'SELECT 1 FORMAT JSON',
  });

  if (!resp.ok) {
    const text = await resp.text();
    console.error('Authentication failed: ' + text.split('\n')[0]);
    process.exit(1);
  }

  await saveConfig({ user, password, logged_in_at: new Date().toISOString() });
  console.log('Login successful. Config saved to ' + CONFIG_FILE);
  console.log('');
  console.log('Try:');
  console.log('  klickhaus status');
  console.log('  klickhaus errors');
}

async function cmdStatus() {
  const where = 'timestamp >= now() - INTERVAL 1 HOUR';
  const sql = `
SELECT
  sum(weight) as total_requests,
  sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) as cnt_4xx,
  sumIf(weight, \`response.status\` >= 500) as cnt_5xx,
  round(sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) * 100.0 / sum(weight), 2) as rate_4xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as rate_5xx
FROM ${DATABASE}.delivery
WHERE ${where}`;

  const topErrorsSql = `
SELECT
  \`request.host\` as host,
  sum(weight) as total,
  sumIf(weight, \`response.status\` >= 500) as errors_5xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as error_rate
FROM ${DATABASE}.delivery
WHERE ${where}
GROUP BY host
HAVING errors_5xx > 0
ORDER BY errors_5xx DESC
LIMIT 10`;

  const [overview, topErrors] = await Promise.all([
    runQuery(sql),
    runQuery(topErrorsSql),
  ]);

  const row = overview.data[0];
  const result = {
    period: 'last 1 hour',
    total_requests: parseInt(row.total_requests),
    errors_4xx: parseInt(row.cnt_4xx),
    errors_5xx: parseInt(row.cnt_5xx),
    rate_4xx_pct: parseFloat(row.rate_4xx),
    rate_5xx_pct: parseFloat(row.rate_5xx),
    top_error_hosts: topErrors.data.map(r => ({
      host: r.host,
      total: parseInt(r.total),
      errors_5xx: parseInt(r.errors_5xx),
      error_rate_pct: parseFloat(r.error_rate),
    })),
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdErrors(args) {
  const { opts } = parseArgs(args);
  // Default to all errors (4xx + 5xx) when no status filter is given.
  // Honor --status=4xx, --status=5xx, or a specific code.
  let errorFilter;
  let errorMetricExpr;
  if (!opts.status) {
    errorFilter = '`response.status` >= 400';
    errorMetricExpr = '`response.status` >= 500';
  } else if (opts.status === '5xx') {
    errorFilter = '`response.status` >= 500';
    errorMetricExpr = '`response.status` >= 500';
  } else if (opts.status === '4xx') {
    errorFilter = '`response.status` >= 400 AND `response.status` < 500';
    errorMetricExpr = '`response.status` >= 400 AND `response.status` < 500';
  } else {
    const code = parseInt(opts.status);
    if (isNaN(code)) {
      console.error('Invalid --status value: ' + opts.status + '. Use 4xx, 5xx, or an exact code.');
      process.exit(1);
    }
    errorFilter = '`response.status` = ' + code;
    errorMetricExpr = '`response.status` = ' + code;
  }
  // Build base WHERE without status filter so the denominator is total traffic.
  const baseWhere = buildWhereClause({ ...opts, status: null });

  const sql = `
SELECT
  \`request.host\` as host,
  toString(\`response.status\`) as status,
  \`request.url\` as path,
  sum(weight) as cnt
FROM ${DATABASE}.${opts.table}
WHERE ${baseWhere} AND ${errorFilter}
GROUP BY host, status, path
ORDER BY cnt DESC
LIMIT ${opts.limit}`;

  const hostSql = `
SELECT
  \`request.host\` as host,
  sum(weight) as total,
  sumIf(weight, ${errorMetricExpr}) as errors,
  round(sumIf(weight, ${errorMetricExpr}) * 100.0 / sum(weight), 2) as error_rate
FROM ${DATABASE}.${opts.table}
WHERE ${baseWhere}
GROUP BY host
HAVING errors > 0
ORDER BY errors DESC
LIMIT 10`;

  const statusSql = `
SELECT
  toString(\`response.status\`) as status,
  sum(weight) as cnt
FROM ${DATABASE}.${opts.table}
WHERE ${baseWhere} AND ${errorFilter}
GROUP BY status
ORDER BY cnt DESC
LIMIT 20`;

  const [details, byHost, byStatus] = await Promise.all([
    runQuery(sql),
    runQuery(hostSql),
    runQuery(statusSql),
  ]);

  const result = {
    table: opts.table,
    range: opts.range,
    host_filter: opts.host || '(all)',
    status_filter: opts.status || '(4xx+5xx)',
    by_host: byHost.data.map(r => ({
      host: r.host,
      total: parseInt(r.total),
      errors: parseInt(r.errors),
      error_rate_pct: parseFloat(r.error_rate),
    })),
    by_status: byStatus.data.map(r => ({
      status: r.status,
      count: parseInt(r.cnt),
    })),
    top_error_paths: details.data.map(r => ({
      host: r.host,
      status: r.status,
      path: r.path,
      count: parseInt(r.cnt),
    })),
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdTimeseries(args) {
  const { opts } = parseArgs(args);
  const tr = TIME_RANGES[opts.range];
  const where = buildWhereClause(opts);

  const sql = `
SELECT
  ${tr.bucket} as t,
  sumIf(weight, \`response.status\` < 400) as cnt_ok,
  sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) as cnt_4xx,
  sumIf(weight, \`response.status\` >= 500) as cnt_5xx
FROM ${DATABASE}.${opts.table}
WHERE ${where}
GROUP BY t
ORDER BY t`;

  const data = await runQuery(sql);

  const result = {
    table: opts.table,
    range: opts.range,
    host_filter: opts.host || '(all)',
    points: data.data.map(r => ({
      time: r.t,
      ok: parseInt(r.cnt_ok),
      '4xx': parseInt(r.cnt_4xx),
      '5xx': parseInt(r.cnt_5xx),
    })),
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdBreakdown(args) {
  const { opts, positional } = parseArgs(args);
  const dimension = positional[0];

  if (!dimension) {
    console.error('Usage: klickhaus breakdown <dimension> [--table=...] [--range=...] [--host=...]');
    console.error('Dimensions: ' + Object.keys(DIMENSIONS).join(', '));
    process.exit(1);
  }

  if (!DIMENSIONS[dimension]) {
    console.error('Unknown dimension: ' + dimension);
    console.error('Valid dimensions: ' + Object.keys(DIMENSIONS).join(', '));
    process.exit(1);
  }

  const col = DIMENSIONS[dimension];
  const where = buildWhereClause(opts);

  const sql = `
SELECT
  ${col} as dim,
  sum(weight) as cnt,
  sumIf(weight, \`response.status\` < 400) as cnt_ok,
  sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) as cnt_4xx,
  sumIf(weight, \`response.status\` >= 500) as cnt_5xx
FROM ${DATABASE}.${opts.table}
WHERE ${where}
GROUP BY dim
ORDER BY cnt DESC
LIMIT ${opts.limit}`;

  const data = await runQuery(sql);

  const result = {
    table: opts.table,
    range: opts.range,
    dimension: dimension,
    host_filter: opts.host || '(all)',
    rows: data.data.map(r => ({
      value: r.dim,
      total: parseInt(r.cnt),
      ok: parseInt(r.cnt_ok),
      '4xx': parseInt(r.cnt_4xx),
      '5xx': parseInt(r.cnt_5xx),
    })),
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdLogs(args) {
  const { opts } = parseArgs(args);
  const where = buildWhereClause(opts);

  const columns = [
    'timestamp',
    '`response.status`',
    '`request.method`',
    '`request.host`',
    '`request.url`',
    '`cdn.cache_status`',
    '`response.headers.content_type`',
    '`helix.request_type`',
    '`helix.backend_type`',
    '`request.headers.x_forwarded_host`',
    '`response.headers.x_error`',
    '`client.ip`',
    '`cdn.datacenter`',
    'weight',
  ];

  const sql = `
SELECT ${columns.join(', ')}
FROM ${DATABASE}.${opts.table}
WHERE ${where}
ORDER BY timestamp DESC
LIMIT ${opts.limit}`;

  const data = await runQuery(sql);

  const result = {
    table: opts.table,
    range: opts.range,
    host_filter: opts.host || '(all)',
    status_filter: opts.status || '(all)',
    count: data.data.length,
    logs: data.data,
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdInvestigate(args) {
  const { opts } = parseArgs(args);
  const where = buildWhereClause(opts);

  // First get an overview
  const overviewSql = `
SELECT
  sum(weight) as total,
  sumIf(weight, \`response.status\` >= 500) as errors_5xx,
  sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) as errors_4xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as rate_5xx
FROM ${DATABASE}.${opts.table}
WHERE ${where}`;

  const overview = await runQuery(overviewSql);
  const ov = overview.data[0];

  // Run breakdowns on key dimensions to find error contributors
  const investigateDimensions = ['host', 'status', 'path', 'cache', 'datacenter', 'request_type', 'backend_type'];
  const findings = {};

  for (const dim of investigateDimensions) {
    const col = DIMENSIONS[dim];
    const sql = `
SELECT
  ${col} as dim,
  sum(weight) as cnt,
  sumIf(weight, \`response.status\` >= 500) as cnt_5xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as error_rate
FROM ${DATABASE}.${opts.table}
WHERE ${where}
GROUP BY dim
HAVING cnt_5xx > 0
ORDER BY cnt_5xx DESC
LIMIT 5`;

    const data = await runQuery(sql);
    if (data.data.length > 0) {
      findings[dim] = data.data.map(r => ({
        value: r.dim,
        total: parseInt(r.cnt),
        errors_5xx: parseInt(r.cnt_5xx),
        error_rate_pct: parseFloat(r.error_rate),
      }));
    }
  }

  const result = {
    table: opts.table,
    range: opts.range,
    host_filter: opts.host || '(all)',
    overview: {
      total_requests: parseInt(ov.total),
      errors_5xx: parseInt(ov.errors_5xx),
      errors_4xx: parseInt(ov.errors_4xx),
      rate_5xx_pct: parseFloat(ov.rate_5xx),
    },
    findings: findings,
  };

  console.log(JSON.stringify(result, null, 2));
}

async function cmdMonday(args) {
  // Default to 24h for monday (parseArgs default of 1h doesn't fit here),
  // unless the user passed --range= explicitly.
  const hasRangeFlag = args.some(a => a.startsWith('--range='));
  const argsWithRange = hasRangeFlag ? args : args.concat(['--range=24h']);
  const { opts } = parseArgs(argsWithRange);
  const range = opts.range;
  const tr = TIME_RANGES[range];
  const where = 'timestamp >= now() - ' + tr.interval;

  // Overview
  const overviewSql = `
SELECT
  sum(weight) as total,
  sumIf(weight, \`response.status\` >= 500) as errors_5xx,
  sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) as errors_4xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as rate_5xx,
  round(sumIf(weight, \`response.status\` >= 400 AND \`response.status\` < 500) * 100.0 / sum(weight), 2) as rate_4xx
FROM ${DATABASE}.delivery
WHERE ${where}`;

  // Top error hosts (denominator is total host traffic, not just 5xx)
  const errorHostsSql = `
SELECT
  \`request.host\` as host,
  sum(weight) as total,
  sumIf(weight, \`response.status\` >= 500) as errors_5xx,
  round(sumIf(weight, \`response.status\` >= 500) * 100.0 / sum(weight), 2) as error_rate
FROM ${DATABASE}.delivery
WHERE ${where}
GROUP BY host
HAVING errors_5xx > 0
ORDER BY errors_5xx DESC
LIMIT 10`;

  // Top error status codes
  const statusSql = `
SELECT
  toString(\`response.status\`) as status,
  sum(weight) as cnt
FROM ${DATABASE}.delivery
WHERE ${where} AND \`response.status\` >= 400
GROUP BY status
ORDER BY cnt DESC
LIMIT 10`;

  const [overview, errorHosts, statusBreakdown] = await Promise.all([
    runQuery(overviewSql),
    runQuery(errorHostsSql),
    runQuery(statusSql),
  ]);

  const ov = overview.data[0];

  const result = {
    source: 'klickhaus',
    period: range,
    summary: {
      total_requests: parseInt(ov.total),
      errors_5xx: parseInt(ov.errors_5xx),
      errors_4xx: parseInt(ov.errors_4xx),
      rate_5xx_pct: parseFloat(ov.rate_5xx),
      rate_4xx_pct: parseFloat(ov.rate_4xx),
    },
    top_error_hosts: errorHosts.data.map(r => ({
      host: r.host,
      total: parseInt(r.total),
      errors_5xx: parseInt(r.errors_5xx),
      error_rate_pct: parseFloat(r.error_rate),
    })),
    status_breakdown: statusBreakdown.data.map(r => ({
      status: r.status,
      count: parseInt(r.cnt),
    })),
  };

  console.log(JSON.stringify(result, null, 2));
}

async function readStdin() {
  return new Promise(function(resolve, reject) {
    if (process.stdin.isTTY) { resolve(''); return; }
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', function(chunk) { data += chunk; });
    process.stdin.on('end', function() { resolve(data); });
    process.stdin.on('error', reject);
  });
}

async function cmdQuery(args) {
  let sql = '';
  const fileArg = args.find(a => a.startsWith('--file='));
  if (fileArg) {
    const filePath = fileArg.split('=').slice(1).join('=');
    sql = await fs.readFile(filePath, 'utf8');
  } else if (args.length > 0) {
    sql = args.join(' ');
  } else if (!process.stdin.isTTY) {
    sql = await readStdin();
  }
  sql = sql.trim();
  if (!sql) {
    console.error('Usage: klickhaus query <SQL>');
    console.error('       klickhaus query --file=query.sql');
    console.error('       cat query.sql | klickhaus query');
    process.exit(1);
  }

  const data = await runQuery(sql);
  console.log(JSON.stringify(data, null, 2));
}

function showHelp() {
  console.log('klickhaus — CDN incident investigation tool\n');
  console.log('Setup:');
  console.log('  login                        Store ClickHouse credentials\n');
  console.log('Commands:');
  console.log('  status                       Quick health check (last hour)');
  console.log('  errors [FLAGS]               Error breakdown by host, path, status');
  console.log('  timeseries [FLAGS]           Time series of ok/4xx/5xx traffic');
  console.log('  breakdown <DIM> [FLAGS]      Top N values for a dimension');
  console.log('  logs [FLAGS]                 Individual log entries');
  console.log('  investigate [FLAGS]          Automatic multi-dimension error analysis');
  console.log('  monday [FLAGS]               Monday protocol summary');
  console.log('  query <SQL>                  Run arbitrary SQL (also accepts --file= or stdin)\n');
  console.log('Flags:');
  console.log('  --table=TABLE                delivery, admin, backend, da (default: delivery)');
  console.log('  --range=RANGE                15m, 1h, 12h, 24h, 3d, 7d (default: 1h)');
  console.log('  --host=HOST                  Filter to specific host');
  console.log('  --status=STATUS              Filter: 4xx, 5xx, or exact code (e.g. 503)');
  console.log('  --limit=N                    Number of results (default: 20)\n');
  console.log('Dimensions:');
  console.log('  ' + Object.keys(DIMENSIONS).join(', ') + '\n');
  console.log('Examples:');
  console.log('  klickhaus status');
  console.log('  klickhaus errors --range=1h --host=www.example.com');
  console.log('  klickhaus timeseries --range=24h');
  console.log('  klickhaus breakdown host --range=1h');
  console.log('  klickhaus breakdown status --host=www.example.com');
  console.log('  klickhaus logs --status=5xx --limit=10');
  console.log('  klickhaus investigate --range=1h --host=www.example.com');
  console.log('  klickhaus monday --range=24h');
  console.log('  klickhaus query "SELECT count() FROM delivery WHERE timestamp >= now() - INTERVAL 1 HOUR"');
  console.log('  klickhaus query --file=query.sql');
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
  case 'status':
    await cmdStatus();
    break;
  case 'errors':
    await cmdErrors(args);
    break;
  case 'timeseries':
    await cmdTimeseries(args);
    break;
  case 'breakdown':
    await cmdBreakdown(args);
    break;
  case 'logs':
    await cmdLogs(args);
    break;
  case 'investigate':
    await cmdInvestigate(args);
    break;
  case 'monday':
    await cmdMonday(args);
    break;
  case 'query':
    await cmdQuery(args);
    break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
