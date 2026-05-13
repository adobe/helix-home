// Adobe On-Call — incident management skill
// Uses ServiceNow Table API + UX Databroker from workspace page context.

const DOMAIN = 'adobe.service-now.com';
const ONCALL_PATH = '/x/adosy/on-call/home';
const INCIDENT_TABLE = 'x_adosy_adb_on_ca_incident';
const DATABROKER_ENDPOINT = '/api/now/uxf/databroker/exec';
const SUMMARY_DEFINITION_ID = '1a7dd83d1b31b114fde1c8451a4bcba3';
const DEFAULT_GROUP_ID = 'f3483b5047f11610c49b3d54116d4348'; // AEM - Helix v2

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
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("GET", "' + path.replace(/"/g, '\\"') + '");',
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
  const bodyStr = JSON.stringify(body).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("PATCH", "' + path.replace(/"/g, '\\"') + '");',
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
    '  xhr.send("' + bodyStr + '");',
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
  const payloadStr = JSON.stringify(payload).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const code = [
    'new Promise(function(r) {',
    '  var xhr = new XMLHttpRequest();',
    '  xhr.open("POST", "' + DATABROKER_ENDPOINT + '");',
    '  xhr.setRequestHeader("Accept", "application/json");',
    '  xhr.setRequestHeader("Content-Type", "application/json");',
    '  xhr.setRequestHeader("X-UserToken", window.g_ck || "");',
    '  xhr.onload = function() { r(xhr.responseText); };',
    '  xhr.onerror = function() { r(JSON.stringify({__error: "network"})); };',
    '  xhr.timeout = 15000;',
    '  xhr.ontimeout = function() { r(JSON.stringify({__error: "timeout"})); };',
    '  xhr.send("' + payloadStr + '");',
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

var STATE_LABELS = { '1': 'Open', '2': 'Work in Progress', '3': 'Resolved', '4': 'Closed', '60': 'Re-Open' };

async function cmdIncidents(args) {
  var stateFilter = '1,2,60';
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--state=')) {
      var stateMap = { 'open': '1', 'wip': '2', 're-open': '60', 'resolved': '3', 'closed': '4', 'all': '1,2,3,4,60' };
      var val = args[i].split('=')[1];
      stateFilter = stateMap[val] || val;
    }
  }
  var query = 'assignment_groupDYNAMICd6435e965f510100a9ad2572f2b47744^active=true^stateIN' + stateFilter + '^ORDERBYDESCopened_at';
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
      var stateMap = { 'open': '1', 'wip': '2', 'resolved': '3', 'closed': '4', 're-open': '60' };
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

async function cmdShifts() {
  var payload = [{ type: 'GRAPHQL', definitionSysId: SUMMARY_DEFINITION_ID, inputValues: { groupSysId: { type: 'JSON_LITERAL', value: null }, userSysId: { type: 'JSON_LITERAL', value: null } }, pipelineId: 'get_on_call_summary_info' }];
  var data = await databrokerExec(payload);
  var info = data.result && data.result[0] && data.result[0].executionResult && data.result[0].executionResult.output && data.result[0].executionResult.output.data && data.result[0].executionResult.output.data.xAdosyAdbOnCa && data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall && data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getSummaryCardInfo;
  if (!info) { console.error('Could not retrieve schedule info.'); process.exit(1); }
  var result = {
    currently_oncall: info.userIsOnCall,
    current_shifts: info.usersCurrentShifts || [],
    upcoming_shifts: (info.usersFutureShifts || []).map(function(s) {
      return { start: s.startDate, end: s.endDate, roster: s.roster, group: s.groupName };
    })
  };
  console.log(JSON.stringify(result, null, 2));
}

async function cmdWhoIsOnCall(args) {
  var groupSysId = DEFAULT_GROUP_ID;
  for (var i = 0; i < args.length; i++) {
    if (args[i].startsWith('--group=')) groupSysId = args[i].split('=')[1];
  }
  var payload = [{ type: 'GRAPHQL', definitionSysId: SUMMARY_DEFINITION_ID, inputValues: { groupSysId: { type: 'JSON_LITERAL', value: groupSysId }, userSysId: { type: 'JSON_LITERAL', value: null } }, pipelineId: 'get_on_call_summary_info' }];
  var data = await databrokerExec(payload);
  var info = data.result && data.result[0] && data.result[0].executionResult && data.result[0].executionResult.output && data.result[0].executionResult.output.data && data.result[0].executionResult.output.data.xAdosyAdbOnCa && data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall && data.result[0].executionResult.output.data.xAdosyAdbOnCa.adbOnCall.getSummaryCardInfo;
  if (!info) { console.error('Could not retrieve on-call info.'); process.exit(1); }
  console.log(JSON.stringify(info.whoIsOnCall || [], null, 2));
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
  var query = 'assignment_groupDYNAMICd6435e965f510100a9ad2572f2b47744^active=true^stateIN1,2,60^ORDERBYDESCopened_at';
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
  console.log('  shifts                        View your upcoming shifts');
  console.log('  who [--group=ID]              Show who is on-call');
  console.log('  monday [--limit N] [--date Nd]  Monday protocol output\n');
  console.log('States: open, wip, re-open, resolved, closed, all');
  console.log('');
  console.log('Examples:');
  console.log('  oncall incidents');
  console.log('  oncall get OCINC2145403');
  console.log('  oncall ack OCINC2145403');
  console.log('  oncall who');
  console.log('  oncall shifts');
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
  case 'shifts': await cmdShifts(); break;
  case 'who': await cmdWhoIsOnCall(args); break;
  case 'whoisoncall': await cmdWhoIsOnCall(args); break;
  case 'monday': await cmdMonday(args); break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
