// New Relic — NerdGraph / NRQL / Synthetics investigation tool
// Companion to klickhaus: klickhaus answers "what is the CDN doing",
// newrelic answers "what is alerting, and why".

// Runtime bridges: in the SLICC .jsh runtime the former bare `exec` and `fs`
// globals are exposed via require('sliccy:exec') / require('fs'), and the
// per-skill gitignored `.config` through require('sliccy:skill').
const skill = require('sliccy:skill');
const exec = require('sliccy:exec');
const fs = require('fs');

const API_ENDPOINT = 'https://api.newrelic.com/graphql';
const UI_HOST = 'one.newrelic.com';
const DEFAULT_ACCOUNT = 2429334; // DX_Project_Helix
const BACKUP_DIR = '/shared/newrelic-script-backups';

// NRQL time ranges -> SINCE clause
const RANGES = {
  '5m': '5 minutes ago',
  '15m': '15 minutes ago',
  '30m': '30 minutes ago',
  '1h': '1 hour ago',
  '3h': '3 hours ago',
  '6h': '6 hours ago',
  '12h': '12 hours ago',
  '24h': '1 day ago',
  '3d': '3 days ago',
  '7d': '7 days ago',
};

const MONITOR_PERIODS = [
  'EVERY_MINUTE', 'EVERY_5_MINUTES', 'EVERY_10_MINUTES', 'EVERY_15_MINUTES',
  'EVERY_30_MINUTES', 'EVERY_HOUR', 'EVERY_6_HOURS', 'EVERY_12_HOURS', 'EVERY_DAY',
];

// --- Config ---

async function loadConfig() {
  // skill.config() returns a Promise; it must be awaited before any `||`
  // fallback, because the raw Promise is always truthy.
  return (await skill.config()) || {};
}

async function saveConfig(updates) {
  return await skill.config(updates);
}

// --- Transport ---
//
// Two auth modes:
//   key  — a User API key sent as `Api-Key` to api.newrelic.com. Headless,
//          works without a browser, preferred for automation.
//   tab  — reuse the cookie session of an open one.newrelic.com tab and POST
//          to its relative /graphql. No key needed, but requires the browser.
//
// Both speak the same NerdGraph, so every command works in either mode.

let _tabId = null;

// Tab ids go stale when a tab is recreated: tab-list may still show an id whose
// CDP session is gone. Dead ids are remembered so a retry picks a different tab.
const _deadTabs = new Set();

async function findTab() {
  if (_tabId && !_deadTabs.has(_tabId)) {
    const list = await exec('playwright-cli tab-list');
    if (list.stdout.includes(_tabId)) return _tabId;
    _tabId = null;
  }
  const list = await exec('playwright-cli tab-list');
  const re = new RegExp('\\[([A-F0-9]+)\\]\\s+https?://[^\\s]*' + UI_HOST.replace(/\./g, '\\.'), 'g');
  const ids = [];
  let m;
  while ((m = re.exec(list.stdout)) !== null) ids.push(m[1]);
  const usable = ids.find(function (id) { return !_deadTabs.has(id); });
  if (!usable) return null;
  _tabId = usable;
  return _tabId;
}

function isStaleTabError(err) {
  return /Session with given id not found|-32001|Target closed|No target with given id/i.test(String((err && err.message) || err));
}

async function gqlViaTabOnce(query, tabId) {
  // The nr1-ui service header is required or the cookie-authenticated
  // /graphql proxy rejects the request.
  const code = [
    'const r = await fetch("/graphql", {',
    '  method: "POST",',
    '  credentials: "include",',
    '  headers: {',
    '    "Content-Type": "application/json",',
    '    "newrelic-requesting-services": "nr1-ui"',
    '  },',
    '  body: JSON.stringify({ query: ' + JSON.stringify(query) + ' })',
    '});',
    'const text = await r.text();',
    'return JSON.stringify({ __status: r.status, __body: text });',
  ].join('\n');

  const tmpFile = '/shared/.newrelic_eval_' + Date.now() + '.js';
  await fs.writeFile(tmpFile, code);
  const result = await exec('playwright-cli eval-file ' + tmpFile + ' --tab=' + tabId);
  await fs.rm(tmpFile).catch(function () {});

  if (result.exitCode !== 0) {
    throw new Error('Tab eval failed: ' + (result.stderr || '').split('\n')[0]);
  }
  let envelope;
  try {
    envelope = JSON.parse(result.stdout.trim());
  } catch (e) {
    throw new Error('Unparseable tab response: ' + result.stdout.slice(0, 200));
  }
  if (envelope.__status === 401 || envelope.__status === 403) {
    console.error('New Relic session expired. Reload ' + UI_HOST + ' and try again.');
    process.exit(1);
  }
  return JSON.parse(envelope.__body);
}

async function gqlViaTab(query) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const tabId = await findTab();
    if (!tabId) {
      console.error('No ' + UI_HOST + ' tab found. Open one and log in, or use `newrelic login --key=NRAK-...`.');
      process.exit(1);
    }
    try {
      return await gqlViaTabOnce(query, tabId);
    } catch (err) {
      // A recreated tab leaves a dead CDP session behind: drop it and re-list once.
      if (isStaleTabError(err) && attempt === 0) {
        _deadTabs.add(tabId);
        _tabId = null;
        continue;
      }
      throw err;
    }
  }
  throw new Error('unreachable');
}

async function gqlViaKey(query, apiKey) {
  const resp = await fetch(API_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Api-Key': apiKey },
    body: JSON.stringify({ query }),
  });
  if (resp.status === 401 || resp.status === 403) {
    console.error('API key rejected. Re-run `newrelic login --key=NRAK-...`.');
    process.exit(1);
  }
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error('NerdGraph HTTP ' + resp.status + ': ' + text.slice(0, 200));
  }
  return await resp.json();
}

async function gql(query) {
  const config = await loadConfig();
  let body;
  if (config.apiKey) {
    body = await gqlViaKey(query, config.apiKey);
  } else {
    body = await gqlViaTab(query);
  }
  if (body.errors && body.errors.length) {
    // GraphQL-level errors: schema mistakes, permission problems, bad NRQL.
    const messages = body.errors.map(function (e) { return e.message; });
    console.error('NerdGraph error: ' + messages.join(' | '));
    process.exit(1);
  }
  return body.data;
}

// --- Helpers ---

function parseFlags(args) {
  const opts = { positional: [], json: false, confirm: false };
  for (const arg of args) {
    if (arg.startsWith('--account=')) opts.account = parseInt(arg.split('=')[1], 10);
    else if (arg.startsWith('--range=')) opts.range = arg.split('=')[1];
    else if (arg.startsWith('--limit=')) opts.limit = parseInt(arg.split('=')[1], 10);
    else if (arg.startsWith('--file=')) opts.file = arg.split('=').slice(1).join('=');
    else if (arg.startsWith('--state=')) opts.state = arg.split('=')[1].toUpperCase();
    else if (arg.startsWith('--backup=')) opts.backup = arg.split('=').slice(1).join('=');
    else if (arg === '--no-backup') opts.noBackup = true;
    else if (arg === '--failed') opts.failed = true;
    else if (arg === '--json') opts.json = true;
    else if (arg === '--confirm') opts.confirm = true;
    else opts.positional.push(arg);
  }
  return opts;
}

async function accountId(opts) {
  if (opts && opts.account) return opts.account;
  const config = await loadConfig();
  return config.account || DEFAULT_ACCOUNT;
}

function since(range) {
  const key = range || '1h';
  const value = RANGES[key];
  if (!value) {
    console.error('Unknown range: ' + key + '. Use one of: ' + Object.keys(RANGES).join(', '));
    process.exit(1);
  }
  return value;
}

// NRQL string values are single-quoted, so single quotes must be escaped.
function nrqlString(value) {
  return "'" + String(value).replace(/'/g, "\\'") + "'";
}

// String.length is UTF-16 code units; scripts with box-drawing characters are
// meaningfully larger on the wire, so report real bytes.
function byteLen(text) {
  return Buffer.byteLength(String(text), 'utf8');
}

function looksLikeGuid(value) {
  return /^[A-Za-z0-9+/=_-]{30,}$/.test(value) && !value.includes(' ');
}

function table(rows, headers) {
  if (!rows.length) {
    console.log('(no results)');
    return;
  }
  const keys = headers || Object.keys(rows[0]);
  const widths = keys.map(function (k) {
    return Math.max(k.length, ...rows.map(function (r) { return String(r[k] === undefined || r[k] === null ? '' : r[k]).length; }));
  });
  // The final column is left unpadded so long values (URLs) do not trail spaces.
  const pad = function (value, i) {
    const text = String(value === undefined || value === null ? '' : value);
    return i === keys.length - 1 ? text : text.padEnd(widths[i]);
  };
  console.log(keys.map(pad).join('  '));
  console.log(keys.map(function (k, i) {
    return '-'.repeat(i === keys.length - 1 ? k.length : widths[i]);
  }).join('  '));
  for (const row of rows) {
    console.log(keys.map(function (k, i) { return pad(row[k], i); }).join('  '));
  }
}

async function runNrql(account, query) {
  const data = await gql('{ actor { account(id: ' + account + ') { nrql(query: ' + JSON.stringify(query) + ') { results } } } }');
  const acct = data && data.actor && data.actor.account;
  return (acct && acct.nrql && acct.nrql.results) || [];
}

/** Resolve a monitor name or guid to { guid, name, accountId }. */
async function resolveMonitor(nameOrGuid, account) {
  if (looksLikeGuid(nameOrGuid)) {
    const data = await gql('{ actor { entity(guid: ' + JSON.stringify(nameOrGuid) + ') { guid name accountId } } }');
    const e = data.actor.entity;
    if (!e) {
      console.error('No entity with guid ' + nameOrGuid);
      process.exit(1);
    }
    return e;
  }
  const search = "domain = 'SYNTH' AND type = 'MONITOR' AND name = " + nrqlString(nameOrGuid)
    + ' AND accountId = ' + account;
  const data = await gql('{ actor { entitySearch(query: ' + JSON.stringify(search)
    + ') { results { entities { guid name accountId } } } } }');
  const entities = data.actor.entitySearch.results.entities;
  if (!entities.length) {
    console.error('No synthetic monitor named "' + nameOrGuid + '" in account ' + account + '.');
    console.error('Run `newrelic monitors` to list them.');
    process.exit(1);
  }
  if (entities.length > 1) {
    console.error('Ambiguous name, ' + entities.length + ' matches. Use a guid:');
    entities.forEach(function (e) { console.error('  ' + e.guid + '  ' + e.name); });
    process.exit(1);
  }
  return entities[0];
}

// --- Commands ---

async function cmdLogin(args) {
  const opts = parseFlags(args);
  let key = null;
  for (const arg of args) {
    if (arg.startsWith('--key=')) key = arg.split('=').slice(1).join('=');
  }
  const fromTab = args.includes('--from-tab');

  if (!key && !fromTab) {
    console.error('Usage: newrelic login --key=NRAK-... [--account=N]');
    console.error('   or: newrelic login --from-tab [--account=N]');
    console.error('');
    console.error('--key      A User API key (Profile > API keys in the New Relic UI).');
    console.error('           Headless, no browser needed.');
    console.error('--from-tab Reuse the cookie session of an open ' + UI_HOST + ' tab.');
    process.exit(1);
  }

  const updates = { logged_in_at: new Date().toISOString() };
  if (opts.account) updates.account = opts.account;

  if (key) {
    const body = await gqlViaKey('{ actor { user { name email } } }', key);
    if (body.errors && body.errors.length) {
      console.error('Key rejected: ' + body.errors[0].message);
      process.exit(1);
    }
    updates.apiKey = key;
    await saveConfig(updates);
    console.log('Logged in as ' + body.data.actor.user.name + ' <' + body.data.actor.user.email + '> (API key mode).');
  } else {
    // Clearing apiKey drops back to tab mode.
    updates.apiKey = null;
    await saveConfig(updates);
    const data = await gql('{ actor { user { name email } } }');
    console.log('Using the ' + UI_HOST + ' tab session as ' + data.actor.user.name
      + ' <' + data.actor.user.email + '> (tab mode).');
  }
  console.log('Default account: ' + (await accountId(opts)));
  console.log('');
  console.log('Try:');
  console.log('  newrelic issues');
  console.log('  newrelic monitors');
}

async function cmdAccounts(args) {
  const opts = parseFlags(args);
  const filter = (opts.positional[0] || "").toLowerCase();
  const data = await gql('{ actor { accounts { id name } } }');
  let accounts = data.actor.accounts;
  const total = accounts.length;
  if (filter) {
    accounts = accounts.filter(function (a) {
      return a.name.toLowerCase().includes(filter) || String(a.id) === filter;
    });
  }
  if (opts.json) {
    console.log(JSON.stringify(accounts, null, 2));
    return;
  }
  // Some identities can read hundreds of accounts; nudge towards a filter.
  if (!filter && total > 50) {
    console.log('Readable accounts: ' + total + '. Showing the first 50 — pass a filter to narrow, e.g. `newrelic accounts helix`.');
    accounts = accounts.slice(0, 50);
  }
  table(accounts.map(function (a) { return { id: a.id, name: a.name }; }));
}
async function cmdNrql(args) {
  const opts = parseFlags(args);
  let query = opts.positional.join(' ');
  if (opts.file) query = await fs.readFile(opts.file, 'utf8');
  if (!query || !query.trim()) {
    console.error('Usage: newrelic nrql "SELECT count(*) FROM Transaction SINCE 1 hour ago"');
    console.error('   or: newrelic nrql --file=query.nrql');
    process.exit(1);
  }
  const account = await accountId(opts);
  const results = await runNrql(account, query.trim());
  if (opts.json) {
    console.log(JSON.stringify(results, null, 2));
    return;
  }
  table(results);
}

async function cmdIssues(args) {
  const opts = parseFlags(args);
  const account = await accountId(opts);
  const state = opts.state || 'ACTIVATED';
  const data = await gql('{ actor { account(id: ' + account + ') { aiIssues { issues(filter: {states: '
    + state + '}) { issues { issueId title state priority activatedAt closedAt entityNames } } } } } }');
  const issues = data.actor.account.aiIssues.issues.issues;
  if (opts.json) {
    console.log(JSON.stringify(issues, null, 2));
    return;
  }
  if (!issues.length) {
    console.log('No ' + state + ' issues in account ' + account + '.');
    return;
  }
  table(issues.map(function (i) {
    return {
      issueId: i.issueId,
      priority: i.priority,
      state: i.state,
      opened: i.activatedAt ? new Date(i.activatedAt).toISOString().replace('T', ' ').slice(0, 16) : '',
      entity: (i.entityNames || []).join(','),
      title: Array.isArray(i.title) ? i.title.join(' ') : i.title,
    };
  }));
}

async function cmdMonitors(args) {
  const opts = parseFlags(args);
  const account = await accountId(opts);
  const search = "domain = 'SYNTH' AND type = 'MONITOR' AND accountId = " + account;
  const data = await gql('{ actor { entitySearch(query: ' + JSON.stringify(search)
    + ') { results { entities { guid name'
    + ' ... on SyntheticMonitorEntityOutline { monitorType period monitorSummary { status locationsFailing locationsRunning } } } } } } }');
  const entities = data.actor.entitySearch.results.entities;
  if (opts.json) {
    console.log(JSON.stringify(entities, null, 2));
    return;
  }
  table(entities.map(function (e) {
    const s = e.monitorSummary || {};
    return {
      name: e.name,
      type: e.monitorType || '',
      period: e.period === undefined ? '' : e.period,
      status: s.status || '',
      failing: s.locationsFailing === undefined ? '' : s.locationsFailing + '/' + s.locationsRunning,
      guid: e.guid,
    };
  }));
}

async function cmdMonitor(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  if (!name) {
    console.error('Usage: newrelic monitor <NAME|GUID>');
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  const data = await gql('{ actor { entity(guid: ' + JSON.stringify(m.guid) + ') { name guid accountId'
    + ' tags { key values }'
    + ' ... on SyntheticMonitorEntity { monitorType period monitorSummary { status locationsFailing locationsRunning } } } } }');
  const e = data.actor.entity;
  if (opts.json) {
    console.log(JSON.stringify(e, null, 2));
    return;
  }
  const s = e.monitorSummary || {};
  console.log('Name    : ' + e.name);
  console.log('Guid    : ' + e.guid);
  console.log('Account : ' + e.accountId);
  console.log('Type    : ' + (e.monitorType || ''));
  console.log('Period  : ' + (e.period === undefined ? '' : e.period) + ' min');
  console.log('Status  : ' + (s.status || '') + '  failing ' + s.locationsFailing + '/' + s.locationsRunning);
  console.log('');
  console.log('Tags:');
  (e.tags || []).forEach(function (t) {
    console.log('  ' + t.key + ': ' + t.values.join(', '));
  });
}

async function cmdScript(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  if (!name) {
    console.error('Usage: newrelic script <NAME|GUID>');
    console.error('Prints the source of a scripted monitor (SCRIPT_API or SCRIPT_BROWSER).');
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  const data = await gql('{ actor { account(id: ' + (m.accountId || account) + ') { synthetics {'
    + ' script(monitorGuid: ' + JSON.stringify(m.guid) + ') { text } } } } }');
  const script = data.actor.account.synthetics.script;
  if (!script || !script.text) {
    console.error('No script for ' + m.name + ' (is it a scripted monitor?).');
    process.exit(1);
  }
  console.log(script.text);
}

async function cmdChecks(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  if (!name) {
    console.error('Usage: newrelic checks <NAME|GUID> [--range=1h] [--failed]');
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  const window = since(opts.range);

  const summary = await runNrql(account, 'SELECT count(*) FROM SyntheticCheck WHERE monitorName = '
    + nrqlString(m.name) + ' FACET result SINCE ' + window);
  console.log('Results (' + (opts.range || '1h') + '):');
  table(summary.map(function (r) { return { result: r.facet, count: r.count }; }));

  const errors = await runNrql(account, 'SELECT count(*) FROM SyntheticCheck WHERE monitorName = '
    + nrqlString(m.name) + " AND result = 'FAILED' FACET error SINCE " + window + ' LIMIT 10');
  if (errors.length) {
    console.log('');
    console.log('Failure messages:');
    table(errors.map(function (r) { return { error: r.facet, count: r.count }; }));
  }

  const locations = await runNrql(account, 'SELECT count(*) FROM SyntheticCheck WHERE monitorName = '
    + nrqlString(m.name) + (opts.failed ? " AND result = 'FAILED'" : '')
    + ' FACET locationLabel SINCE ' + window + ' LIMIT 30');
  if (locations.length) {
    console.log('');
    console.log('By location' + (opts.failed ? ' (failures only)' : '') + ':');
    table(locations.map(function (r) { return { location: r.facet, count: r.count }; }));
  }
}

async function cmdRequests(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  if (!name) {
    console.error('Usage: newrelic requests <NAME|GUID> [--range=1h]');
    console.error('Breaks a scripted monitor down by outbound URL and HTTP status —');
    console.error('the fastest way to see which dependency is failing.');
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  const window = since(opts.range);
  const limit = opts.limit || 25;

  const rows = await runNrql(account, 'SELECT count(*) FROM SyntheticRequest WHERE monitorName = '
    + nrqlString(m.name) + ' FACET URL, responseCode SINCE ' + window + ' LIMIT ' + limit);
  if (opts.json) {
    console.log(JSON.stringify(rows, null, 2));
    return;
  }
  console.log('Outbound requests (' + (opts.range || '1h') + '):');
  table(rows.map(function (r) {
    const facet = Array.isArray(r.facet) ? r.facet : [r.facet, ''];
    return { status: facet[1], count: r.count, url: facet[0] };
  }), ['status', 'count', 'url']);
  console.log('');
  console.log('Note: SyntheticRequest carries no response body. For the reason behind');
  console.log('a status, read the failure message (`newrelic checks`) or the script.');
}

async function cmdCredentials(args) {
  const opts = parseFlags(args);
  const account = await accountId(opts);
  // Secure credentials are entities (type SECURE_CRED), not a field under
  // account.synthetics. Values are write-only and never returned by any API —
  // only the key name is visible.
  const search = "domain = 'SYNTH' AND type = 'SECURE_CRED' AND accountId = " + account;
  const data = await gql('{ actor { entitySearch(query: ' + JSON.stringify(search)
    + ') { results { entities { guid name } } } } }');
  const creds = data.actor.entitySearch.results.entities;
  if (opts.json) {
    console.log(JSON.stringify(creds, null, 2));
    return;
  }
  table(creds.map(function (c) { return { key: c.name, guid: c.guid }; }));
  console.log("");
  console.log("Values are write-only: no API returns a secure credential's contents.");
}
async function cmdSetPeriod(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  const period = (opts.positional[1] || '').toUpperCase();
  if (!name || !period) {
    console.error('Usage: newrelic set-period <NAME|GUID> <PERIOD> --confirm');
    console.error('Periods: ' + MONITOR_PERIODS.join(', '));
    process.exit(1);
  }
  if (!MONITOR_PERIODS.includes(period)) {
    console.error('Unknown period: ' + period);
    console.error('Periods: ' + MONITOR_PERIODS.join(', '));
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  if (!opts.confirm) {
    console.error('Refusing to modify ' + m.name + ' without --confirm.');
    console.error('This changes a live monitor: newrelic set-period ' + JSON.stringify(name) + ' ' + period + ' --confirm');
    process.exit(1);
  }
  // Note: this mutation takes only `guid` and `monitor`. Passing accountId is a
  // schema error. Fields left out of `monitor` are preserved.
  const data = await gql('mutation { syntheticsUpdateScriptApiMonitor(guid: ' + JSON.stringify(m.guid)
    + ', monitor: { period: ' + period + ' }) { errors { description } monitor { name period status } } }');
  const res = data.syntheticsUpdateScriptApiMonitor;
  if (res.errors && res.errors.length) {
    console.error('Update failed: ' + res.errors.map(function (e) { return e.description; }).join('; '));
    process.exit(1);
  }
  console.log('Updated ' + res.monitor.name + ' -> period ' + res.monitor.period + ' (' + res.monitor.status + ').');
}

async function cmdSetScript(args) {
  const opts = parseFlags(args);
  const name = opts.positional[0];
  if (!name || !opts.file) {
    console.error('Usage: newrelic set-script <NAME|GUID> --file=path/to/script.js --confirm');
    console.error('Back up the current script first: newrelic script <NAME> > backup.js');
    process.exit(1);
  }
  const account = await accountId(opts);
  const m = await resolveMonitor(name, account);
  const script = await fs.readFile(opts.file, 'utf8');
  if (!script || !script.trim()) {
    console.error('Refusing to deploy an empty script.');
    process.exit(1);
  }
  if (!opts.confirm) {
    console.error('Refusing to overwrite the script of ' + m.name + ' without --confirm.');
    console.error(byteLen(script) + ' bytes staged from ' + opts.file + '.');
    console.error('The live script is backed up automatically; --no-backup skips it.');
    process.exit(1);
  }

  // Snapshot the live script first. A failed backup aborts the deploy unless the
  // caller explicitly opted out, so a monitor is never overwritten blind.
  if (!opts.noBackup) {
    const slug = m.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const target = opts.backup || BACKUP_DIR + '/' + slug + '-' + stamp + '.js';
    let current;
    try {
      const cur = await gql('{ actor { account(id: ' + (m.accountId || account) + ') { synthetics {'
        + ' script(monitorGuid: ' + JSON.stringify(m.guid) + ') { text } } } } }');
      current = cur.actor.account.synthetics.script && cur.actor.account.synthetics.script.text;
    } catch (e) {
      console.error('Could not read the current script for backup: ' + e.message);
      console.error('Re-run with --no-backup to deploy anyway.');
      process.exit(1);
    }
    if (!current) {
      console.error('The monitor has no readable script to back up.');
      console.error('Re-run with --no-backup to deploy anyway.');
      process.exit(1);
    }
    try {
      if (!opts.backup) await fs.mkdir(BACKUP_DIR, { recursive: true }).catch(function () {});
      await fs.writeFile(target, current);
    } catch (e) {
      console.error('Backup write failed (' + target + '): ' + e.message);
      console.error('Re-run with --no-backup to deploy anyway.');
      process.exit(1);
    }
    console.log('Backed up ' + byteLen(current) + ' bytes to ' + target);
  }
  const data = await gql('mutation { syntheticsUpdateScriptApiMonitor(guid: ' + JSON.stringify(m.guid)
    + ', monitor: { script: ' + JSON.stringify(script) + ' }) { errors { description } monitor { name period status } } }');
  const res = data.syntheticsUpdateScriptApiMonitor;
  if (res.errors && res.errors.length) {
    console.error('Deploy failed: ' + res.errors.map(function (e) { return e.description; }).join('; '));
    process.exit(1);
  }
  console.log('Deployed ' + byteLen(script) + ' bytes to ' + res.monitor.name + '.');
  console.log('Verify after one period: newrelic checks ' + JSON.stringify(res.monitor.name) + ' --range=15m');
}

async function cmdGraphql(args) {
  const opts = parseFlags(args);
  let query = opts.positional.join(' ');
  if (opts.file) query = await fs.readFile(opts.file, 'utf8');
  if (!query || !query.trim()) {
    console.error('Usage: newrelic graphql "{ actor { user { name } } }"');
    console.error('   or: newrelic graphql --file=query.graphql');
    process.exit(1);
  }
  const data = await gql(query.trim());
  console.log(JSON.stringify(data, null, 2));
}

// --- Help ---

function showHelp() {
  console.log('newrelic — NerdGraph, NRQL and Synthetics from the command line\n');
  console.log('Setup:');
  console.log('  login --key=NRAK-...         Store a User API key (headless)');
  console.log('  login --from-tab             Reuse an open ' + UI_HOST + ' tab session\n');
  console.log('Commands:');
  console.log('  accounts [FILTER]            Accounts this identity can read (FILTER by name)');
  console.log('  issues [--state=ACTIVATED]   Alert issues (ACTIVATED, CLOSED, CREATED)');
  console.log('  monitors                     Synthetic monitors with status and period');
  console.log('  monitor <NAME|GUID>          Monitor detail, including tags');
  console.log('  script <NAME|GUID>           Print a scripted monitor\'s source');
  console.log('  checks <NAME> [--failed]     Check results, failure messages, locations');
  console.log('  requests <NAME>              Outbound URLs and HTTP statuses');
  console.log('  credentials                  Secure credential keys (never values)');
  console.log('  nrql <QUERY>                 Run NRQL');
  console.log('  graphql <QUERY>              Run raw NerdGraph\n');
  console.log('Mutations (all require --confirm):');
  console.log('  set-period <NAME> <PERIOD>   Change how often a monitor runs');
  console.log('  set-script <NAME> --file=F   Replace a scripted monitor\'s source (backs up first)\n');
  console.log('Flags:');
  console.log('  --account=N                  Account id (default: config, else ' + DEFAULT_ACCOUNT + ')');
  console.log('  --range=RANGE                ' + Object.keys(RANGES).join(', ') + ' (default: 1h)');
  console.log('  --limit=N                    Result cap');
  console.log('  --file=PATH                  Read query or script from a file');
  console.log('  --json                       Raw JSON instead of a table');
  console.log('  --backup=PATH                Where set-script saves the previous version');
  console.log('  --no-backup                  Skip the set-script backup');
  console.log('  --confirm                    Required for anything that writes\n');
  console.log('Examples:');
  console.log('  newrelic issues');
  console.log('  newrelic monitors');
  console.log('  newrelic checks "RUM Script Delivery" --range=3h');
  console.log('  newrelic requests "RUM Script Delivery" --range=1h');
  console.log('  newrelic script "RUM Script Delivery" > backup.js');
  console.log('  newrelic nrql "SELECT count(*) FROM SyntheticCheck FACET result SINCE 1 hour ago"');
  console.log('  newrelic set-period "RUM Script Delivery" EVERY_5_MINUTES --confirm');
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
  case 'accounts':
    await cmdAccounts(args);
    break;
  case 'issues':
    await cmdIssues(args);
    break;
  case 'monitors':
    await cmdMonitors(args);
    break;
  case 'monitor':
    await cmdMonitor(args);
    break;
  case 'script':
    await cmdScript(args);
    break;
  case 'checks':
    await cmdChecks(args);
    break;
  case 'requests':
    await cmdRequests(args);
    break;
  case 'credentials':
    await cmdCredentials(args);
    break;
  case 'nrql':
    await cmdNrql(args);
    break;
  case 'graphql':
    await cmdGraphql(args);
    break;
  case 'set-period':
    await cmdSetPeriod(args);
    break;
  case 'set-script':
    await cmdSetScript(args);
    break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
