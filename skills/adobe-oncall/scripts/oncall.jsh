// Adobe On-Call — incident management skill
// Uses ServiceNow Table API + UX Databroker from workspace page context.

// Runtime bridges: in the SLICC .jsh runtime the former bare `exec` and `fs`
// globals are exposed via require('sliccy:exec') / require('fs'). Without these
// imports every command throws `ReferenceError: exec is not defined` (in
// ensureTab) / `fs is not defined` (in the XHR eval helpers).
const exec = require('sliccy:exec');
const fs = require('fs');

const DOMAIN = 'adobe.service-now.com';
const ONCALL_PATH = '/x/adosy/on-call/home';
const INCIDENT_TABLE = 'x_adosy_adb_on_ca_incident';
const DATABROKER_ENDPOINT = '/api/now/uxf/databroker/exec';
const CALENDAR_DEFINITION_ID = 'b90d6f7a1be2fd10fde1c8451a4bcba6';
const DEFAULT_GROUP_ID = 'f3483b5047f11610c49b3d54116d4348'; // AEM - Helix v2
const EMEA_ROSTER_ID = 'a99c33f58360c7d00479abe0deaad33d';
const NA_ROSTER_ID = '6f4df71c47f11610c49b3d54116d4335';

// Watch state (runtime artifacts, gitignored): the active watch config and the
// set of incident sys_ids already surfaced (for dedup across polls).
const WATCH_STATE = '/workspace/skills/adobe-oncall/.watch.json';
const WATCH_SEEN = '/workspace/skills/adobe-oncall/.watch-seen.json';

// Single-quote shell-escape for building `exec` command strings safely.
function shq(s) { return "'" + String(s).replace(/'/g, "'\\''") + "'"; }

let _tabId = null;

// --- Tab management ---

async function ensureTab() {
  if (_tabId) {
    const list = await exec('playwright-cli tab-list');
    if (list.stdout.includes(_tabId)) return _tabId;
    _tabId = null;
  }
  const list = await exec('playwright-cli tab-list');
  const re = new RegExp('\\[([A-F0-9]+)\\]\\s+https?://[^\\s]*' + DOMAIN.replace(/\./g, '\\.') + '/x/adosy');
  const match = list.stdout.match(re);
  if (match) {
    _tabId = match[1];
    return _tabId;
  }
  // Try any ServiceNow workspace tab
  const re2 = new RegExp('\\[([A-F0-9]+)\\]\\s+https?://[^\\s]*' + DOMAIN.replace(/\./g, '\\.'));
  const match2 = list.stdout.match(re2);
  if (match2) {
    _tabId = match2[1];
    return _tabId;
  }
  // Open the on-call page
  const r = await exec('playwright-cli open https://' + DOMAIN + ONCALL_PATH);
  const m = r.stdout.match(/targetId:\s*(\S+)\]/);
  _tabId = m ? m[1] : null;
  if (!_tabId) {
    console.error('Failed to open ServiceNow On-Call tab.');
    process.exit(1);
  }
  await new Promise(function(resolve) { setTimeout(resolve, 5000); });
  return _tabId;
}

// --- API via XHR from page context (uses g_ck token automatically) ---

async function apiGet(path) {
  const tabId = await ensureTab();
  const pathLiteral = JSON.stringify(path);
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("GET", ' + pathLiteral + ');',
    '  xhr.setRequestHeader("Accept", "application/json");',
    '  xhr.setRequestHeader("X-UserToken", window.g_ck || "");',
    '  xhr.onload = function() {',
    '    if (xhr.status >= 200 && xhr.status < 300) {',
    '      r(xhr.responseText);',
    '    } else {',
    '      r(JSON.stringify({__error: xhr.status, detail: xhr.responseText.substring(0, 200)}));',
    '    }',
    '  };',
    '  xhr.onerror = function() { r(JSON.stringify({__error: "network"})); };',
    '  xhr.timeout = 15000;',
    '  xhr.ontimeout = function() { r(JSON.stringify({__error: "timeout"})); };',
    '  xhr.send();',
    '})'
  ].join('\n');
  const tmpFile = '/shared/.oncall_eval_' + Date.now() + '.js';
  await fs.writeFile(tmpFile, code);
  const result = await exec('playwright-cli eval-file ' + tmpFile + ' --tab=' + tabId);
  await fs.rm(tmpFile).catch(function() {});
  if (result.exitCode !== 0) {
    if (result.stderr.includes('g_ck') || result.stderr.includes('not defined')) {
      console.error('Session expired. Open ' + DOMAIN + ONCALL_PATH + ' and try again.');
      process.exit(1);
    }
    throw new Error('Eval failed: ' + result.stderr);
  }
  const parsed = JSON.parse(result.stdout.trim());
  if (parsed && parsed.__error) {
    if (parsed.__error === 401 || parsed.__error === 403) {
      console.error('Session expired. Open ' + DOMAIN + ONCALL_PATH + ' and try again.');
      process.exit(1);
    }
    throw new Error('API error ' + parsed.__error + ': ' + (parsed.detail || ''));
  }
  return parsed;
}

async function apiPatch(path, body) {
  const tabId = await ensureTab();
  // Use JSON.stringify twice: once to serialize the body, then again to embed
  // that JSON safely inside a JS string literal in the eval code. This handles
  // newlines, quotes, and other control characters that would otherwise break
  // the generated script (e.g. multi-line --comment= values for work_notes).
  const bodyLiteral = JSON.stringify(JSON.stringify(body));
  const pathLiteral = JSON.stringify(path);
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("PATCH", ' + pathLiteral + ');',
    '  xhr.setRequestHeader("Accept", "application/json");',
    '  xhr.setRequestHeader("Content-Type", "application/json");',
    '  xhr.setRequestHeader("X-UserToken", window.g_ck || "");',
    '  xhr.onload = function() {',
    '    if (xhr.status >= 200 && xhr.status < 300) {',
    '      r(xhr.responseText);',
    '    } else {',
    '      r(JSON.stringify({__error: xhr.status, detail: xhr.responseText.substring(0, 200)}));',
    '    }',
    '  };',
    '  xhr.onerror = function() { r(JSON.stringify({__error: "network"})); };',
    '  xhr.timeout = 15000;',
    '  xhr.ontimeout = function() { r(JSON.stringify({__error: "timeout"})); };',
    '  xhr.send(' + bodyLiteral + ');',
    '})'
  ].join('\n');
  const tmpFile = '/shared/.oncall_eval_' + Date.now() + '.js';
  await fs.writeFile(tmpFile, code);
  const result = await exec('playwright-cli eval-file ' + tmpFile + ' --tab=' + tabId);
  await fs.rm(tmpFile).catch(function() {});
  if (result.exitCode !== 0) throw new Error('Eval failed: ' + result.stderr);
  const parsed = JSON.parse(result.stdout.trim());
  if (parsed && parsed.__error) throw new Error('API error ' + parsed.__error);
  return parsed;
}

async function databrokerExec(payload) {
  const tabId = await ensureTab();
  const payloadLiteral = JSON.stringify(JSON.stringify(payload));
  const endpointLiteral = JSON.stringify(DATABROKER_ENDPOINT);
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("POST", ' + endpointLiteral + ');',
    '  xhr.setRequestHeader("Accept", "application/json");',
    '  xhr.setRequestHeader("Content-Type", "application/json");',
    '  xhr.setRequestHeader("X-UserToken", window.g_ck || "");',
    '  xhr.onload = function() { r(xhr.responseText); };',
    '  xhr.onerror = function() { r(JSON.stringify({__error: "network"})); };',
    '  xhr.timeout = 15000;',
    '  xhr.ontimeout = function() { r(JSON.stringify({__error: "timeout"})); };',
    '  xhr.send(' + payloadLiteral + ');',
    '})'
  ].join('\n');
  const tmpFile = '/shared/.oncall_eval_' + Date.now() + '.js';
  await fs.writeFile(tmpFile, code);
  const result = await exec('playwright-cli eval-file ' + tmpFile + ' --tab=' + tabId);
  await fs.rm(tmpFile).catch(function() {});
  if (result.exitCode !== 0) throw new Error('Eval failed: ' + result.stderr);
  return JSON.parse(result.stdout.trim());
}

// --- Resolve OCINC number to sys_id ---

async function resolveIncident(numberOrId) {
  if (/^[a-f0-9]{32}$/i.test(numberOrId)) return numberOrId;
  var path = '/api/now/table/' + INCIDENT_TABLE + '?sysparm_query=number=' + numberOrId + '&sysparm_fields=sys_id&sysparm_limit=1';
  var data = await apiGet(path);
  if (data.result && data.result.length > 0) return data.result[0].sys_id;
  console.error('Could not resolve ' + numberOrId + '.');
  process.exit(1);
}

// --- Get current user sys_id from page context ---

async function getCurrentUser() {
  const tabId = await ensureTab();
  const code = 'new Promise(function(r) { r(window.NOW && window.NOW.user ? JSON.stringify({sys_id: window.NOW.user.userID || window.NOW.user_id, name: window.NOW.user.name || window.NOW.user_name}) : JSON.stringify({sys_id: null})); })';
  const tmpFile = '/shared/.oncall_user_' + Date.now() + '.js';
  await fs.writeFile(tmpFile, code);
  const result = await exec('playwright-cli eval-file ' + tmpFile + ' --tab=' + tabId);
  await fs.rm(tmpFile).catch(function() {});
  if (result.exitCode !== 0) return null;
  try { return JSON.parse(result.stdout.trim()); } catch(e) { return null; }
}

// --- Commands ---

var STATE_LABELS = { '1': 'Open', '-5': 'Pending', '2': 'Work in Progress', '6': 'Resolved', '8': 'Cancelled', '60': 'Re-Open' };

async function cmdIncidents(args) {
  var stateFilter = '1,2,60';
  var groupId = null;
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--state=')) {
      var stateMap = { 'open': '1', 'pending': '-5', 'wip': '2', 'resolved': '6', 'cancelled': '8', 'canceled': '8', 're-open': '60', 'reopen': '60', 'all': '1,-5,2,6,8,60' };
      var val = args[i].split('=')[1];
      stateFilter = stateMap[val] || val;
    }
    if (args[i].startsWith('--group=')) {
      groupId = args[i].split('=')[1];
    }
  }
  var groupClause = groupId
    ? 'assignment_group=' + groupId
    : 'assignment_groupDYNAMICd6435e965f510100a9ad2572f2b47744';
  var query = groupClause + '^active=true^stateIN' + stateFilter + '^ORDERBYDESCopened_at';
  var fields = 'number,short_description,state,priority,assigned_to,assignment_group,opened_at,sys_id';
  var path = '/api/now/table/' + INCIDENT_TABLE + '?sysparm_query=' + encodeURIComponent(query) + '&sysparm_fields=' + fields + '&sysparm_limit=20&sysparm_display_value=true';
  var data = await apiGet(path);
  var results = data.result || [];
  if (results.length === 0) {
    console.log('No active on-call incidents.');
    return;
  }
  var incidents = results.map(function(r) {
    return {
      number: r.number,
      description: r.short_description.trim(),
      state: (typeof r.state === 'object' ? r.state.display_value : STATE_LABELS[r.state]) || r.state,
      priority: typeof r.priority === 'object' ? r.priority.display_value : r.priority,
      assigned_to: typeof r.assigned_to === 'object' ? r.assigned_to.display_value : r.assigned_to,
      group: typeof r.assignment_group === 'object' ? r.assignment_group.display_value : r.assignment_group,
      opened: r.opened_at,
      sys_id: r.sys_id
    };
  });
  console.log(JSON.stringify(incidents, null, 2));
}

async function cmdGet(numberOrId) {
  if (!numberOrId) { console.error('Usage: adobe-oncall get <OCINC_NUMBER>'); process.exit(1); }
  var sysId = await resolveIncident(numberOrId);
  var path = '/api/now/table/' + INCIDENT_TABLE + '/' + sysId + '?sysparm_display_value=true';
  var data = await apiGet(path);
  var r = data.result;
  if (!r) { console.error('Incident not found.'); process.exit(1); }
  var result = {
    number: r.number,
    description: (r.short_description || '').trim(),
    state: r.state,
    priority: r.priority,
    assigned_to: r.assigned_to,
    assignment_group: r.assignment_group,
    opened_at: r.opened_at,
    updated_at: r.sys_updated_on,
    acknowledged: r.u_acknowledged,
    acknowledged_by: r.u_acknowledged_by,
    work_notes: r.work_notes,
    comments: r.comments
  };
  console.log(JSON.stringify(result, null, 2));
}

async function cmdAck(numberOrId) {
  if (!numberOrId) { console.error('Usage: adobe-oncall ack <OCINC_NUMBER>'); process.exit(1); }
  var sysId = await resolveIncident(numberOrId);
  var user = await getCurrentUser();
  var body = { u_acknowledged: 'true', state: '2' };
  if (user && user.sys_id) body.assigned_to = user.sys_id;
  var path = '/api/now/table/' + INCIDENT_TABLE + '/' + sysId;
  await apiPatch(path, body);
  console.log('Acknowledged ' + numberOrId + '.');
}

async function cmdUpdate(numberOrId, args) {
  if (!numberOrId) { console.error('Usage: adobe-oncall update <OCINC_NUMBER> --state=STATE'); process.exit(1); }
  var sysId = await resolveIncident(numberOrId);
  var body = {};
  var comment = '';
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--state=')) {
      // On-Call Incident (x_adosy_adb_on_ca_incident) state values, verified live
      // against the table's choice list / g_form: 1 Open, -5 Pending,
      // 2 Work in Progress, 6 Resolved, 8 Cancelled, 60 Re-Open. Unknown names
      // fall through as a raw value so a numeric state still works.
      var stateMap = { 'open': '1', 'pending': '-5', 'wip': '2', 'resolved': '6', 'cancelled': '8', 'canceled': '8', 're-open': '60', 'reopen': '60' };
      var val = args[i].split('=')[1];
      body.state = stateMap[val] || val;
    }
    if (args[i].startsWith('--comment=')) {
      comment = args[i].split('=').slice(1).join('=');
    }
  }
  if (comment) body.work_notes = comment;
  if (Object.keys(body).length === 0) { console.error('Nothing to update. Use --state= or --comment='); process.exit(1); }
  var path = '/api/now/table/' + INCIDENT_TABLE + '/' + sysId;
  await apiPatch(path, body);
  console.log('Updated ' + numberOrId + '.');
}

// Fetch active on-call incidents for the group (raw result rows).
async function fetchActiveIncidents(stateFilter) {
  var sf = stateFilter || '1,2,60';
  var query = 'assignment_group=' + DEFAULT_GROUP_ID + '^active=true^stateIN' + sf + '^ORDERBYDESCopened_at';
  var fields = 'number,short_description,state,priority,assigned_to,opened_at,sys_id';
  var path = '/api/now/table/' + INCIDENT_TABLE + '?sysparm_query=' + encodeURIComponent(query) + '&sysparm_fields=' + fields + '&sysparm_limit=50&sysparm_display_value=true';
  var data = await apiGet(path);
  return (data && data.result) || [];
}

function readJsonFile(path, fallback) {
  try { return JSON.parse(require('fs').readFileSync(path, 'utf8')); } catch (e) { return fallback; }
}

// The standing instruction handed to the investigator scoop on each tick.
// Must contain NO single quotes (it is embedded in a shell-escaped --filter).
var WATCH_INSTRUCTION =
  'Adobe on-call watch tick. Run this shell command: oncall watch-poll --json ' +
  '-- it prints a JSON array of NEW (not-yet-investigated) on-call incidents (empty [] if none). ' +
  'If it returns [], do nothing and stop. For each incident, start the investigation immediately so the ' +
  'legwork is done before the human acks: follow the klickhaus RCA playbook (read /workspace/skills/klickhaus/SKILL.md) ' +
  '-- klickhaus status; per-minute 5xx timeseries to find the burst; breakdown x_error to identify the subsystem; ' +
  'localize by cdn.datacenter; scope by host (many tenants=infra, one=customer); confirm ongoing vs recovered. ' +
  'Attribute the failure to the layer that emitted the error, and cross-tabulate to falsify. ' +
  'Then post a concise findings work note to the incident with: oncall update <NUMBER> --comment=<your findings>. ' +
  'Do NOT change incident state or resolve it -- leave that to the human.';

// oncall watch --scoop <name> [--interval <min>] [--force]
// Sets up a cron poller that wakes the given scoop; the scoop runs
// `oncall watch-poll` and investigates any new incident. Also: `oncall watch`
// (no args) shows status.
async function cmdWatch(args) {
  var scoop = null, interval = 2, force = false;
  for (var i = 0; i < (args || []).length; i++) {
    if (args[i].startsWith('--scoop=')) scoop = args[i].split('=')[1];
    else if (args[i] === '--scoop') scoop = args[i + 1];
    else if (args[i].startsWith('--interval=')) interval = parseInt(args[i].split('=')[1], 10) || 2;
    else if (args[i] === '--interval') interval = parseInt(args[i + 1], 10) || 2;
    else if (args[i] === '--force') force = true;
  }

  var existing = readJsonFile(WATCH_STATE, null);

  // No scoop → status view.
  if (!scoop) {
    if (existing) {
      console.log(JSON.stringify({ watching: true, scoop: existing.scoop, interval_min: existing.interval, cron_id: existing.cronId, since: existing.createdAt }, null, 2));
    } else {
      console.error('Not watching. Usage: oncall watch --scoop <name> [--interval <min>] [--force]');
      process.exit(1);
    }
    return;
  }

  if (!/^[a-zA-Z0-9_-]+$/.test(scoop)) { console.error('Invalid --scoop "' + scoop + '". Alphanumeric, dash, underscore only.'); process.exit(1); }
  if (!(interval >= 1 && interval <= 59)) { console.error('--interval must be 1-59 (minutes).'); process.exit(1); }

  if (existing && !force) {
    console.error('Already watching (scoop: ' + existing.scoop + ', every ' + existing.interval + ' min). Use --force to replace.');
    process.exit(1);
  }
  if (existing && existing.cronId) {
    await exec('crontask delete ' + shq(existing.cronId)).catch(function () {});
  }

  var cron = '*/' + interval + ' * * * *';
  var filterJs = '() => ({ source: "oncall-watch", ts: Date.now(), instruction: "' + WATCH_INSTRUCTION + '" })';
  var createCmd = 'crontask create --name ' + shq('oncall-watch') + ' --scoop ' + shq(scoop) + ' --cron ' + shq(cron) + ' --filter ' + shq(filterJs);
  var res = await exec(createCmd);
  if (res.exitCode !== 0) { console.error('Failed to create cron task:', res.stderr || res.stdout); process.exit(1); }
  var idMatch = res.stdout.match(/^ID:\s*(\S+)/m);
  if (!idMatch) { console.error('Could not parse cron task id from:', res.stdout.substring(0, 200)); process.exit(1); }
  var cronId = idMatch[1];

  // Seed the seen-set with CURRENTLY-active incidents so the watch only fires
  // on genuinely new ones (existing open incidents are already known).
  var nowIso = new Date().toISOString();
  var seen = {};
  try {
    var current = await fetchActiveIncidents();
    for (var j = 0; j < current.length; j++) seen[current[j].sys_id] = nowIso;
  } catch (e) { /* best effort */ }
  require('fs').writeFileSync(WATCH_SEEN, JSON.stringify({ updated: nowIso, seen: seen }, null, 2));
  require('fs').writeFileSync(WATCH_STATE, JSON.stringify({ scoop: scoop, interval: interval, cronId: cronId, createdAt: nowIso }, null, 2));

  console.log('Watching on-call incidents -> scoop "' + scoop + '" (every ' + interval + ' min).');
  console.log('  Cron task: ' + cronId);
  console.log('  Seeded ' + Object.keys(seen).length + ' currently-active incident(s) as already-seen.');
  console.log('  The scoop will run `oncall watch-poll` each tick and investigate new incidents.');
  console.log('  Stop with: oncall unwatch');
}

// oncall watch-poll [--json]
// Returns on-call incidents not yet surfaced (dedup via WATCH_SEEN). Idempotent;
// safe to run every tick. This is the detection engine the watcher scoop calls.
async function cmdWatchPoll(args) {
  var asJson = (args || []).indexOf('--json') !== -1;
  var store = readJsonFile(WATCH_SEEN, { seen: {} });
  var seen = store.seen || {};
  var incidents = await fetchActiveIncidents();

  var nowMs = Date.now();
  var nowIso = new Date().toISOString();
  var fresh = [];
  for (var i = 0; i < incidents.length; i++) {
    var r = incidents[i];
    if (!seen[r.sys_id]) {
      fresh.push({
        number: r.number,
        short_description: (r.short_description || '').trim(),
        priority: typeof r.priority === 'object' ? r.priority.display_value : r.priority,
        state: typeof r.state === 'object' ? r.state.display_value : r.state,
        opened: typeof r.opened_at === 'object' ? r.opened_at.display_value : r.opened_at,
        sys_id: r.sys_id
      });
    }
    seen[r.sys_id] = seen[r.sys_id] || nowIso;
  }
  // Prune seen entries older than 7 days to bound the file.
  for (var k in seen) { if (nowMs - new Date(seen[k]).getTime() > 7 * 86400000) delete seen[k]; }
  require('fs').writeFileSync(WATCH_SEEN, JSON.stringify({ updated: nowIso, seen: seen }, null, 2));

  if (asJson) { console.log(JSON.stringify(fresh)); return; }
  if (fresh.length === 0) { console.log('No new incidents.'); return; }
  console.log(fresh.length + ' new incident(s):');
  for (var m = 0; m < fresh.length; m++) console.log('  ' + fresh[m].number + '  [' + fresh[m].priority + ']  ' + fresh[m].short_description);
}

// oncall unwatch — tear down the active watch.
async function cmdUnwatch() {
  var existing = readJsonFile(WATCH_STATE, null);
  if (!existing) { console.log('Not watching.'); return; }
  if (existing.cronId) await exec('crontask delete ' + shq(existing.cronId)).catch(function () {});
  try { require('fs').unlinkSync(WATCH_STATE); } catch (e) {}
  try { require('fs').unlinkSync(WATCH_SEEN); } catch (e) {}
  console.log('Stopped watching (scoop: ' + existing.scoop + ', cron task ' + (existing.cronId || 'n/a') + ' deleted).');
}

// Fetch on-call calendar spans (events) for a UTC date window. Each event:
// { id, title:"Name (ROSTER)", start, end (ms-epoch strings), roster, group }.
// Returns the events array or null. Shared by `shifts` (and mirrors the query
// `who` uses).
async function getCalendarSpans(startDate, endDate, groupSysId) {
  var payload = [{
    type: 'GRAPHQL',
    definitionSysId: CALENDAR_DEFINITION_ID,
    inputValues: { input: { type: 'JSON_LITERAL', value: { startDate: startDate, endDate: endDate, groupIds: groupSysId, userIds: null } } },
    pipelineId: 'get_calendar_spans_1'
  }];
  var data = await databrokerExec(payload);
  return (data.result && data.result[0] && data.result[0].executionResult &&
    data.result[0].executionResult.output && data.result[0].executionResult.output.data &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getCalendarSpans &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getCalendarSpans.events) || null;
}

// Resolve the signed-in user's display name. window.NOW.user carries only a
// sys_id in this instance, so fall back to a sys_user lookup.
async function resolveUserName() {
  var u = await getCurrentUser();
  if (u && u.name) return u.name;
  if (u && u.sys_id) {
    var data = await apiGet('/api/now/table/sys_user/' + u.sys_id + '?sysparm_fields=name');
    if (data && data.result && data.result.name) return data.result.name;
  }
  return null;
}

// Your upcoming on-call shifts. Rebuilt on the calendar-spans query (the old
// summary-card GraphQL returned no data). `--days=N` sets the window (default 14).
async function cmdShifts(args) {
  var days = 14;
  for (var i = 0; i < (args || []).length; i++) {
    if (args[i].startsWith('--days=')) days = parseInt(args[i].split('=')[1], 10) || 14;
  }

  var name = await resolveUserName();
  if (!name) { console.error('Could not determine the signed-in user.'); process.exit(1); }

  var now = new Date();
  var pad = function(n) { return n < 10 ? '0' + n : '' + n; };
  var d0 = now.getUTCFullYear() + '-' + pad(now.getUTCMonth() + 1) + '-' + pad(now.getUTCDate());
  var t2 = new Date(now.getTime() + days * 86400000);
  var d1 = t2.getUTCFullYear() + '-' + pad(t2.getUTCMonth() + 1) + '-' + pad(t2.getUTCDate());

  var events = await getCalendarSpans(d0, d1, DEFAULT_GROUP_ID);
  if (!events) { console.error('Could not retrieve on-call calendar.'); process.exit(1); }

  var nowMs = now.getTime();
  var mine = [];
  for (var j = 0; j < events.length; j++) {
    var ev = events[j];
    if (!ev.title || ev.title.indexOf('Shift:') === 0 || ev.title.indexOf('Roster:') === 0) continue;
    if (ev.title.indexOf(name) === -1) continue; // titles are "Name (ROSTER)"
    var startMs = parseInt(ev.start, 10), endMs = parseInt(ev.end, 10);
    var rosterLabel = ev.roster === EMEA_ROSTER_ID ? 'EMEA' : (ev.roster === NA_ROSTER_ID ? 'NA' : ev.roster);
    mine.push({ roster: rosterLabel, type: ev.title.indexOf('Coverage') !== -1 ? 'coverage' : 'shift', start: new Date(startMs).toISOString(), end: new Date(endMs).toISOString(), _s: startMs, _e: endMs });
  }
  mine.sort(function(a, b) { return a._s - b._s; });

  var current = null, upcoming = [];
  for (var k = 0; k < mine.length; k++) {
    var m = mine[k];
    if (nowMs >= m._s && nowMs < m._e) current = m;
    else if (m._s > nowMs) upcoming.push(m);
    delete m._s; delete m._e;
  }

  var result = {
    user: name,
    now: now.toISOString(),
    on_call_now: !!current,
    current_shift: current,
    current_shift_ends: current ? current.end : null,
    upcoming_shifts: upcoming
  };
  console.log(JSON.stringify(result, null, 2));
}

async function cmdWhoIsOnCall(args) {
  var groupSysId = DEFAULT_GROUP_ID;
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--group=')) groupSysId = args[i].split('=')[1];
  }
  // Use the calendar spans API (gets both EMEA and NA)
  var now = new Date();
  var pad = function(n) { return n < 10 ? '0' + n : '' + n; };
  var today = now.getUTCFullYear() + '-' + pad(now.getUTCMonth() + 1) + '-' + pad(now.getUTCDate());
  // Query a 2-day window to cover timezone boundaries
  var tomorrow = new Date(now.getTime() + 86400000);
  var end = tomorrow.getUTCFullYear() + '-' + pad(tomorrow.getUTCMonth() + 1) + '-' + pad(tomorrow.getUTCDate());

  var payload = [{
    type: 'GRAPHQL',
    definitionSysId: CALENDAR_DEFINITION_ID,
    inputValues: {
      input: {
        type: 'JSON_LITERAL',
        value: {
          startDate: today,
          endDate: end,
          groupIds: groupSysId,
          userIds: null
        }
      }
    },
    pipelineId: 'get_calendar_spans_1'
  }];
  var data = await databrokerExec(payload);
  var spans = data.result && data.result[0] && data.result[0].executionResult &&
    data.result[0].executionResult.output && data.result[0].executionResult.output.data &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getCalendarSpans &&
    data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getCalendarSpans.events;
  if (!spans) { console.error('Could not retrieve on-call calendar.'); process.exit(1); }

  var nowMs = now.getTime();
  var currentlyOnCall = [];
  var nextUp = [];

  for (var i = 0; i < spans.length; i++) {
    var ev = spans[i];
    // Skip meta entries (Shift/Roster labels)
    if (ev.title.indexOf('Shift:') === 0 || ev.title.indexOf('Roster:') === 0) continue;
    var startMs = parseInt(ev.start);
    var endMs = parseInt(ev.end);
    var rosterLabel = ev.roster === EMEA_ROSTER_ID ? 'EMEA' : (ev.roster === NA_ROSTER_ID ? 'NA' : ev.roster);
    var isCoverage = ev.title.indexOf('Coverage') !== -1;
    var entry = {
      name: ev.title,
      roster: rosterLabel,
      type: isCoverage ? 'coverage' : 'shift',
      start: new Date(startMs).toISOString(),
      end: new Date(endMs).toISOString()
    };
    if (nowMs >= startMs && nowMs < endMs) {
      currentlyOnCall.push(entry);
    } else if (startMs > nowMs && startMs - nowMs < 24 * 3600 * 1000) {
      nextUp.push(entry);
    }
  }

  // Sort nextUp by start time
  nextUp.sort(function(a, b) { return new Date(a.start) - new Date(b.start); });

  var result = { now: now.toISOString(), currently_on_call: currentlyOnCall };
  if (nextUp.length > 0) result.next_up = nextUp.slice(0, 6);
  console.log(JSON.stringify(result, null, 2));
}

async function cmdHistory(args) {
  var period = 'last_week';
  var groupId = DEFAULT_GROUP_ID;
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--period=')) period = args[i].split('=')[1];
    if (args[i].startsWith('--group=')) groupId = args[i].split('=')[1];
  }
  var timeQuery;
  switch (period) {
    case 'today': timeQuery = 'opened_at>=javascript:gs.beginningOfToday()'; break;
    case 'yesterday': timeQuery = 'opened_at>=javascript:gs.beginningOfYesterday()^opened_at<javascript:gs.beginningOfToday()'; break;
    case 'this_week': timeQuery = 'opened_at>=javascript:gs.beginningOfThisWeek()'; break;
    case 'last_week': timeQuery = 'opened_at>=javascript:gs.beginningOfLastWeek()^opened_at<javascript:gs.endOfLastWeek()'; break;
    case 'this_month': timeQuery = 'opened_at>=javascript:gs.beginningOfThisMonth()'; break;
    case 'last_month': timeQuery = 'opened_at>=javascript:gs.beginningOfLastMonth()^opened_at<javascript:gs.endOfLastMonth()'; break;
    default: timeQuery = 'opened_at>=javascript:gs.beginningOfLastWeek()^opened_at<javascript:gs.endOfLastWeek()'; break;
  }
  var query = 'assignment_group=' + groupId + '^' + timeQuery + '^ORDERBYDESCopened_at';
  var fields = 'number,short_description,state,priority,assigned_to,opened_at';
  var path = '/api/now/table/' + INCIDENT_TABLE + '?sysparm_query=' + encodeURIComponent(query) + '&sysparm_fields=' + fields + '&sysparm_limit=50&sysparm_display_value=true';
  var data = await apiGet(path);
  var results = data.result || [];
  if (results.length === 0) {
    console.log('No incidents found for ' + period + '.');
    return;
  }
  var incidents = results.map(function(r) {
    return {
      number: r.number,
      opened: r.opened_at,
      title: (r.short_description || '').trim(),
      assignee: typeof r.assigned_to === 'object' ? r.assigned_to.display_value : (r.assigned_to || 'Unassigned'),
      priority: typeof r.priority === 'object' ? r.priority.display_value : r.priority,
      state: typeof r.state === 'object' ? r.state.display_value : (STATE_LABELS[r.state] || r.state)
    };
  });
  console.log(JSON.stringify(incidents, null, 2));
}

async function cmdMonday(args) {
  var limit = 50;
  var date = '7d';
  for (var i = 0; i < args.length; i++) {
    if (args[i] === '--limit' && args[i + 1]) { limit = parseInt(args[i + 1]); i++; }
    if (args[i] === '--date' && args[i + 1]) { date = args[i + 1]; i++; }
    if (args[i].startsWith('--limit=')) limit = parseInt(args[i].split('=')[1]);
    if (args[i].startsWith('--date=')) date = args[i].split('=')[1];
  }
  var dateMatch = /^(\d+)d$/.exec(date);
  if (!dateMatch) {
    console.error('Invalid --date value: ' + date + '. Expected Nd (e.g. 7d).');
    process.exit(1);
  }
  var days = parseInt(dateMatch[1]);
  var sinceMs = Date.now() - days * 24 * 3600 * 1000;
  var since = new Date(sinceMs);
  var pad = function(n) { return n < 10 ? '0' + n : '' + n; };
  var sinceStr = since.getUTCFullYear() + '-' + pad(since.getUTCMonth() + 1) + '-' + pad(since.getUTCDate())
    + ' ' + pad(since.getUTCHours()) + ':' + pad(since.getUTCMinutes()) + ':' + pad(since.getUTCSeconds());
  // Constrain to incidents updated within the requested date window.
  var query = 'assignment_groupDYNAMICd6435e965f510100a9ad2572f2b47744'
    + '^stateIN1,2,60'
    + '^sys_updated_on>=' + sinceStr
    + '^ORDERBYDESCsys_updated_on';
  var fields = 'number,short_description,state,priority,assigned_to,assignment_group,opened_at,sys_updated_on,sys_id';
  var path = '/api/now/table/' + INCIDENT_TABLE + '?sysparm_query=' + encodeURIComponent(query) + '&sysparm_fields=' + fields + '&sysparm_limit=' + limit + '&sysparm_display_value=true';
  var data = await apiGet(path);
  var results = data.result || [];
  var items = results.map(function(r) {
    return {
      id: 'oncall-' + r.sys_id,
      source: 'adobe-oncall',
      type: 'incident',
      title: (r.short_description || '').trim(),
      subtitle: r.number + ' (' + (STATE_LABELS[r.state] || r.state) + ')',
      url: 'https://' + DOMAIN + ONCALL_PATH + '?id=incident&sys_id=' + r.sys_id,
      ts: (r.sys_updated_on || r.opened_at || '').replace(' ', 'T') + 'Z',
      body: (r.short_description || '').trim(),
      participants: [],
      meta: {
        state: STATE_LABELS[r.state] || r.state,
        priority: typeof r.priority === 'object' ? r.priority.display_value : r.priority,
        group: typeof r.assignment_group === 'object' ? r.assignment_group.display_value : r.assignment_group,
        number: r.number
      }
    };
  });
  console.log(JSON.stringify(items, null, 2));
}

function showHelp() {
  console.log('oncall — Adobe On-Call incident management\n');
  console.log('Commands:');
  console.log('  incidents [--state=STATE]     List active on-call incidents');
  console.log('  get <NUMBER>                  View incident details');
  console.log('  ack <NUMBER>                  Acknowledge an incident');
  console.log('  update <NUMBER> --state=STATE [--comment=TEXT]');
  console.log('                                Update incident state');
  console.log('  shifts [--days=N]             View your upcoming shifts (default 14 days)');
  console.log('  who [--group=ID]              Show who is on-call');
  console.log('  watch --scoop <name> [--interval <min>] [--force]');
  console.log('                                Auto-investigate new incidents via a scoop (default every 2 min)');
  console.log('  watch-poll [--json]           List new (un-surfaced) incidents; used by the watcher scoop');
  console.log('  unwatch                       Stop watching');
  console.log('  history [--period=PERIOD]     Incidents for a time period');
  console.log('  monday [--limit N] [--date Nd]  Monday protocol output\n');
  console.log('Periods: today, yesterday, this_week, last_week (default), this_month, last_month');
  console.log('States: open, wip, re-open, resolved, closed, all');
  console.log('');
  console.log('Examples:');
  console.log('  oncall incidents');
  console.log('  oncall get OCINC2145403');
  console.log('  oncall ack OCINC2145403');
  console.log('  oncall who');
  console.log('  oncall shifts');
  console.log('  oncall watch --scoop oncall-investigator --interval 2');
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
  case 'incidents': await cmdIncidents(args); break;
  case 'get': await cmdGet(args[0]); break;
  case 'ack': await cmdAck(args[0]); break;
  case 'update': await cmdUpdate(args[0], args.slice(1)); break;
  case 'shifts': await cmdShifts(args); break;
  case 'watch': await cmdWatch(args); break;
  case 'watch-poll': await cmdWatchPoll(args); break;
  case 'unwatch': await cmdUnwatch(); break;
  case 'who': await cmdWhoIsOnCall(args); break;
  case 'whoisoncall': await cmdWhoIsOnCall(args); break;
  case 'history': await cmdHistory(args); break;
  case 'monday': await cmdMonday(args); break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
