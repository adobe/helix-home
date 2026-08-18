// change — wrap any command in an Adobe ServiceNow Change Management Request.
//
//   change [root params] run <command> [args...]
//
// Everything before `run` configures the CMR. Everything after `run` is executed
// verbatim and recorded in the ticket as the paper trail. `change` exits with the
// wrapped command's exit code.
//
// Two transports:
//   --via=servicenow (default) — every call runs in the page context of a
//     logged-in adobe.service-now.com tab, with X-UserToken: window.g_ck.
//     Needs no secrets at all.
//   --via=ipaas — headless Adobe iPaaS Change Management API. Needs IPAAS_API_KEY
//     plus the IMS client triple (see references/ipaas.md). Unproven, no creds yet.

const exec = require('sliccy:exec');
const browser = require('sliccy:browser');
const skill = require('sliccy:skill');
const cli = require('sliccy:cli');
const c = require('sliccy:color');
const fs = require('fs');
const fmt = require('sliccy:fmt');

// ─── Constants proven against the live instance (CHG005367969, Closed/Successful) ───

const SN_HOST = 'adobe.service-now.com';
const SN_ORIGIN = 'https://' + SN_HOST;
const FORM_VIEW = 'ADBChangeForm';

// state codes, confirmed live via /api/sn_chg_rest/change/{id}/nextstates
const STATE = {
  '-5': 'New', '-4': 'Assess', '-3': 'Authorize', '-2': 'Scheduled',
  '-1': 'Implement', '0': 'Review', '3': 'Closed', '4': 'Canceled',
};
const S_NEW = '-5', S_ASSESS = '-4', S_IMPLEMENT = '-1', S_REVIEW = '0', S_CLOSED = '3', S_CANCELED = '4';

// u_hosting_location is a reference field: the label lives on the referenced
// record, the change_request row stores a sys_id. Labels not in this map are
// passed through verbatim and the read-back verification will catch a miss.
const HOSTING_LOCATIONS = { USA1: '199c4601870c1990ae3497983cbb3543' };

// Choice fields store a RAW value; the form and every read with
// sysparm_display_value=true show a LABEL. The Table API stores whatever string it is
// handed, label included, so posting «Maintenance» instead of «maintenance» produces a
// record that looks right on screen but does not match any report, filter or business
// rule keyed on the raw value. That is a silent data-quality defect, not a cosmetic one:
// CHG005367969 carries exactly that damage. So everything is normalised to raw here.
//
// Sources: raw values for the plain choice fields come from
// GET /api/now/ui/meta/change_request (authoritative label/value pairs); the three
// glide_static_list fields (u_cr_reason_justification,
// u_production_validation_testing_method, u_environment) are not in that payload and
// sys_choice.value is ACL-hidden for reads, so each raw value below was confirmed by
// *querying* sys_choice for it (?sysparm_query=element=…^value=…) and by reading the
// UI-created record CHG005367859. Note ServiceNow string queries are case-insensitive,
// so the token shape is proven while the case comes from the UI-written record.
const CHOICES = {
  u_customer_impact: { none: 'No Impact', Low: 'Unnoticeable', Medium: 'Degradation', High: 'Outage / Extended Downtime' },
  u_change_complexity: { 'Straight Forward': 'Straight Forward', Complex: 'Complex', 'Very Complex': 'Very Complex' },
  u_backout_plan_type: { 'Roll back': 'Roll back', 'Roll forward': 'Roll forward' },
  u_tenant_type: { Multi: 'Multi', Single: 'Single' },
  u_risk_type: { Minor: 'Minor', Major: 'Major', Standard: 'Standard' },
  u_change_target_type: { technical_service: 'Technical Service', device_group: 'Device Group' },
  u_classification: { configuration: 'Configuration', software: 'Software', hardware: 'Hardware' },
  close_code: { successful: 'Successful', 'successful with issues': 'Successful with issues', unsuccessful: 'Unsuccessful' },
  type: { standard: 'Standard', normal: 'Normal', emergency: 'Emergency', expedited: 'Urgent', latent: 'Latent (Violated)' },
  u_cr_reason_justification: {
    maintenance: 'Maintenance', patching: 'Patching', security: 'Security', bug_fixes: 'Bug Fixes',
    new_features: 'New Features', system_upgrade: 'System Upgrade', enhancement: 'Enhancement',
    prevent_service_outage: 'Prevent Service Outage', restore_from_outage: 'Restore from Outage',
    roll_back_partial: 'Roll Back - partial', roll_back_complete: 'Roll Back - complete',
  },
  u_production_validation_testing_method: {
    live_monitoring: 'Live monitoring', smoke: 'Smoke', canary: 'Canary', regression: 'Regression',
    functional_testing: 'Functional Testing', blue_green: 'Blue/ Green', a_b: 'A/B', load: 'Load',
    manual_steps: 'Manual Steps', business: 'Business', operational: 'Operational', splunk: 'Splunk',
    pat: 'PAT - Production Acceptance Testing',
    observability_tools: 'Observability tools (New Relic, AppDynamics, Dynatrace)',
  },
  // «Change is related to an emergency». MANDATORY for New → Assess: without it the form
  // refuses the hop with «The following mandatory fields are not filled in: Change is
  // related to an emergency». Note the raw value differs from the label in CASE
  // (`No - non-emergency` vs «No - Non-Emergency») and the spacing/hyphenation is exact.
  u_change_fixing_cso: {
    'No - non-emergency': 'No - Non-Emergency',
    'Yes - Fix CSO': 'Yes - Fix CSO',
    'Yes - Prevent a CSO': 'Yes - Prevent a CSO',
  },
  u_environment: {
    production: 'Production', stage: 'Stage', qa: 'QA', development: 'Development',
    training: 'Training', fix: 'Fix', 'model office': 'Model Office', 'hot backup': 'Hot Backup',
  },
};

// The three glide_static_list fields hold a COMMA-SEPARATED list, and real records do use
// several values at once (`bug_fixes,maintenance`), so each element is resolved separately.
let NORMALISE_CHOICES = true;

// --cso shorthands. `none` means «not an emergency change», which is a real value, not the
// empty «-- None --» option: leaving the field empty blocks New → Assess.
const CSO_ALIASES = {
  none: 'No - non-emergency', 'non-emergency': 'No - non-emergency', no: 'No - non-emergency',
  fix: 'Yes - Fix CSO', 'fix-cso': 'Yes - Fix CSO',
  prevent: 'Yes - Prevent a CSO', 'prevent-cso': 'Yes - Prevent a CSO',
};

/** Normalise a caller-supplied choice value to its raw form. Accepts the raw value in any
 *  case, or a label, or a comma-separated list of either, and refuses anything else rather
 *  than writing label-shaped damage. --no-normalise-choices turns this into a pass-through
 *  for the case where a local convention has to be matched deliberately. */
function resolveChoice(field, value, flagName) {
  const map = CHOICES[field];
  if (!map || value === undefined || value === null || value === '') return value;
  const given = String(value);
  if (!NORMALISE_CHOICES) return given;
  return given.split(',').map((part) => {
    const one = part.trim();
    if (!one) return null;
    if (Object.prototype.hasOwnProperty.call(map, one)) return one;
    const raw = Object.keys(map).find((k) => k.toLowerCase() === one.toLowerCase());
    if (raw) return raw;
    const byLabel = Object.keys(map).find((k) => map[k].toLowerCase() === one.toLowerCase());
    if (byLabel) return byLabel;
    return cli.die(`${flagName}: ${JSON.stringify(one)} is not a valid ${field} value.\n`
      + 'Allowed raw values (display label in brackets):\n'
      + Object.keys(map).map((k) => `  ${k}${' '.repeat(Math.max(1, 24 - k.length))}[${map[k]}]`).join('\n')
      + '\nSeveral values may be given, comma-separated. --no-normalise-choices sends the'
      + ' value verbatim instead.', { prefix: 'change' });
  }).filter(Boolean).join(',');
}

const DEFAULTS = {
  via: 'servicenow',
  ci: 'a45e845893ffce50b7aef1e01bba10a1',            // cmdb_ci — EDS Delivery
  ciLabel: 'EDS Delivery',
  instance: 'd0ae449893ffce50b7aef1e01bba1049',      // u_service_offering_instance
  hostingLocation: 'USA1',
  tenantType: 'Multi',
  environment: 'production',
  customerImpact: 'none',            // label «No Impact»
  complexity: 'Straight Forward',
  reason: 'maintenance',             // label «Maintenance» — raw value, see CHOICES
  cso: 'No - non-emergency',         // u_change_fixing_cso, mandatory for New → Assess
  backoutType: 'Roll back',
  validation: 'live_monitoring',     // label «Live monitoring» — raw value
  riskType: 'Minor',
  risk: '4',
  snImpact: '2',
  urgency: '2',
  scope: '3',
  chgModel: '74c98c77876939502140b916cebb357c',      // Adobe Change Model
  targetType: 'technical_service',
  classification: 'configuration',
  documentation: 'https://adobe.sharepoint.com/sites/ITChangeManagement/SitePages/Home.aspx',
  actor: 'a3b27bff3755df8047afc8cfc3990e7c',         // deployer/requested_by/submitter
  approver: 'c6c2bfff3755df8047afc8cfc3990ed9',      // u_change_approver
  leadTime: 300,                                     // seconds between now and the planned
                                                     // START. Must be > 0: a change whose
                                                     // planned start is not in the future is
                                                     // reclassified type=latent, which is a
                                                     // different state machine. See
                                                     // references/servicenow.md.
  duration: 600,                                     // planned window length, seconds
  notesMax: 4000,                                    // paper-trail output cap, chars
  impactMinutes: '0',
  closeCode: 'successful',
  stateTimeout: 60,                                  // seconds to wait for a transition
  formTimeout: 30,                                   // seconds to wait for the form to become
                                                     // scriptable (measured: ~9-10 s)
  type: 'standard',
  category: 'Other',
};

const IPAAS_HOSTS = {
  prod: 'ipaasapi.adobe-services.com',
  stage: 'ipaasapi-stage.adobe-services.com',
  dev: 'ipaasapi-dev.adobe-services.com',
};
const IMS_HOSTS = { prod: 'ims-na1.adobelogin.com', stage: 'ims-na1-stg1.adobelogin.com', dev: 'ims-na1-stg1.adobelogin.com' };

// Exit codes. 0 and the wrapped command's own code are the normal cases; everything else is
// deliberate and documented, because a caller in CI can only see the number.
const EXIT_USAGE = 1;   // bad flags, missing arguments, refused values, unexpected errors
const EXIT_GATE = 90;   // the CMR gate failed and the wrapped command NEVER ran

/** Thrown to unwind to the single process.exit() at the bottom of the file. */
class ChangeExit extends Error {
  constructor(code, silent) { super('exit ' + code); this.exitCode = code; this.silent = !!silent; }
}

// ─── Argument handling ─────────────────────────────────────────────────────────
//
// Only `--flag=value` and bare `--flag` are accepted, deliberately: `--flag value`
// would make the boundary between root params and the subcommand ambiguous.

const SUBCOMMANDS = ['run', 'create', 'get', 'notes', 'assess', 'implement', 'review',
  'close', 'cancel', 'states', 'form', 'repair', 'config', 'help'];

function parseFlags(tokens) {
  const flags = {};
  const positional = [];
  for (const t of tokens) {
    if (t.startsWith('--')) {
      const eq = t.indexOf('=');
      if (eq === -1) { flags[t.slice(2)] = true; continue; }
      const key = t.slice(2, eq);
      const val = t.slice(eq + 1);
      // repeated flags accumulate (--force-field=a --force-field=b), comma-joined
      flags[key] = typeof flags[key] === 'string' && flags[key].length ? flags[key] + ',' + val : val;
    } else positional.push(t);
  }
  return { flags, positional };
}

/** Split argv into root flags, the subcommand, and its (unparsed) tail. */
function splitArgv(argv) {
  const root = [];
  let i = 0;
  for (; i < argv.length; i += 1) {
    if (!argv[i].startsWith('--')) break;
    root.push(argv[i]);
  }
  const sub = argv[i] || null;
  const tail = argv.slice(i + 1);
  return { root: parseFlags(root).flags, sub, tail };
}

/** Root flags plus flags written after the subcommand; the later position wins. */
function mergeFlags(rootFlags, tail) {
  return Object.assign({}, rootFlags, parseFlags(tail).flags);
}

function str(v) { return typeof v === 'string' && v.length ? v : undefined; }
function num(v, name) {
  if (v === undefined) return undefined;
  const n = Number(v);
  if (!Number.isFinite(n)) cli.die(`--${name} must be a number, got ${JSON.stringify(v)}`, { prefix: 'change' });
  return n;
}

// ─── Config ────────────────────────────────────────────────────────────────────

async function loadConfig() {
  // skill.config() is a Promise: it must be awaited before any `|| {}` fallback,
  // because the raw Promise is always truthy.
  return (await skill.config()) || {};
}

/** flag → skill config → env → built-in default. */
function resolver(flags, config, envKeys) {
  return function resolve(name, cfgKey, envKey, dflt) {
    const f = flags[name];
    if (typeof f === 'string' && f.length) return f;
    if (f === true) return true;
    if (cfgKey && config[cfgKey] !== undefined && config[cfgKey] !== null && config[cfgKey] !== '') return String(config[cfgKey]);
    if (envKey && envKeys[envKey]) return envKeys[envKey];
    return dflt;
  };
}

// ─── IO seam ───────────────────────────────────────────────────────────────────
//
// Every side effect — tab discovery, page eval, page fetch, child process, clock —
// goes through this object, so the harness can drive the real orchestration code
// against a stub. CHANGE_IO_STUB=<path to .js returning an io object> swaps it.

function realIo() {
  return {
    now: () => new Date(),
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    cwd: () => process.cwd(),

    async listTabs() {
      const r = await exec('playwright-cli tab-list');
      const tabs = [];
      for (const line of (r.stdout || '').split('\n')) {
        const m = line.match(/\[([0-9A-Fa-f]{8,})\]\s+(\S+)/);
        if (m) tabs.push({ targetId: m[1], url: m[2] });
      }
      return tabs;
    },

    async openTab(url) {
      const r = await exec.spawn(['playwright-cli', 'open', url]);
      const m = (r.stdout || '').match(/targetId:\s*([0-9A-Fa-f]{8,})/);
      if (!m) throw new Error('playwright-cli open did not report a targetId: ' + ((r.stdout || '') + (r.stderr || '')).trim().slice(0, 200));
      return { targetId: m[1], url };
    },

    async closeTab(targetId) {
      await exec.spawn(['playwright-cli', 'tab-close', '--tab=' + targetId]);
    },

    pageEval: (tab, expr) => browser.eval(tab, expr),

    pageFetch: (tab, path, opts) => browser.fetch(tab, path, opts),

    // No streaming: the .jsh runtime's exec()/exec.spawn() buffer a child's
    // output and hand it over on exit (exec.start's callbacks do not exist —
    // its `done` only resolves after stdin.end()). Output is echoed in full
    // the moment the command finishes.
    run: (argv) => exec.spawn(argv),

    fetch: (url, opts) => fetch(url, opts),
  };
}

async function loadIo() {
  const p = process.env.CHANGE_IO_STUB;
  if (!p) return realIo();
  const src = await fs.readFile(p);
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const f = new AsyncFunction('require', 'fs', 'console', 'process', src);
  const io = await f(require, fs, console, process);
  if (!io || typeof io.pageFetch !== 'function') throw new Error('CHANGE_IO_STUB did not return an io object');
  return io;
}

// ─── Time formatting ───────────────────────────────────────────────────────────

const p2 = (n) => String(n).padStart(2, '0');
/** ServiceNow internal datetime, always UTC: YYYY-MM-DD HH:MM:SS. */
function snDate(d) {
  return `${d.getUTCFullYear()}-${p2(d.getUTCMonth() + 1)}-${p2(d.getUTCDate())} `
    + `${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}:${p2(d.getUTCSeconds())}`;
}
/** Parse a caller-supplied UTC datetime: «YYYY-MM-DD HH:MM:SS» or ISO 8601. */
function parseUtc(value, flag) {
  const v = String(value).trim();
  const m = v.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?Z?$/);
  if (!m) {
    cli.die(`${flag}: expected «YYYY-MM-DD HH:MM:SS» in UTC (or ISO 8601), got ${JSON.stringify(v)}`,
      { prefix: 'change' });
  }
  return new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)));
}

/** ServiceNow display datetime for this account: MM-DD-YYYY HH:MM:SS. */
function snDisplayDate(d) {
  return `${p2(d.getUTCMonth() + 1)}-${p2(d.getUTCDate())}-${d.getUTCFullYear()} `
    + `${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}:${p2(d.getUTCSeconds())}`;
}

// ─── Page-context code (ServiceNow transport) ──────────────────────────────────

const JS_TOKEN = 'window.g_ck';

// The ServiceNow classic form takes several seconds to become scriptable — measured at
// roughly 9-10 s after playwright-cli open/goto — so this is a readiness PROBE that is
// polled, never a one-shot check. It reports what is missing so a timeout can say why.
function jsFormState(sysId) {
  return `(function(){try{
    var hasForm = typeof g_form !== 'undefined';
    var hasSubmit = typeof gsftSubmit === 'function';
    var ready = document.readyState === 'complete';
    return JSON.stringify({
      ok: hasForm && hasSubmit && ready,
      hasForm: hasForm, hasSubmit: hasSubmit, readyState: document.readyState,
      sysId: hasForm ? g_form.getUniqueValue() : null,
      state: hasForm ? g_form.getValue('state') : null,
      want: ${JSON.stringify(sysId)},
      error: hasForm ? (hasSubmit ? (ready ? null : 'document.readyState is ' + document.readyState) : 'gsftSubmit not present yet') : 'g_form not present yet'
    });
  }catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;
}

function jsSetState(code, relaxMandatory) {
  return `(function(){try{
    if (typeof g_form === 'undefined') return JSON.stringify({ok:false,error:'g_form not present on this page'});
    if (typeof gsftSubmit !== 'function') return JSON.stringify({ok:false,error:'gsftSubmit not present on this page'});
    ${relaxMandatory ? 'g_form.checkMandatory = false;' : ''}
    g_form.setValue('state', ${JSON.stringify(code)});
    gsftSubmit(null, g_form.getFormElement(), 'adb_sysverb_update_and_stay');
    return JSON.stringify({ok:true});
  }catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;
}

function jsReadGlideList(field) {
  return `(function(){try{
    var el=document.getElementById('change_request.'+${JSON.stringify(field)});
    if(!el) return JSON.stringify({ok:false,error:'hidden input not found'});
    return JSON.stringify({ok:true, value: el.value || ''});
  }catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;
}

// glide_list fields ignore g_form.setValue: the hidden input, its sys_display
// mirror and the selected-options <select> all have to be written by hand.
function jsSetGlideList(field, value, display) {
  return `(function(){try{
    var f=${JSON.stringify(field)}, v=${JSON.stringify(value)}, d=${JSON.stringify(display || value)};
    var hidden=document.getElementById('change_request.'+f);
    var mirror=document.getElementById('sys_display.change_request.'+f);
    var sel=document.getElementById('select_0change_request.'+f);
    if(!hidden) return JSON.stringify({ok:false,error:'hidden input change_request.'+f+' not found'});
    hidden.value=v;
    if(mirror) mirror.value=d;
    if(sel){ var o=document.createElement('option'); o.value=v; o.text=d; o.selected=true; sel.appendChild(o); }
    if(typeof g_form!=='undefined'){ try{ g_form.getControl(f); }catch(e){} }
    hidden.dispatchEvent(new Event('change',{bubbles:true}));
    return JSON.stringify({ok:true, hidden:hidden.value, mirror: mirror?mirror.value:null, options: sel?sel.options.length:null});
  }catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;
}

const JS_BANNER = `(function(){try{
  var out=[], seen={};
  var push=function(t){ t=(t||'').replace(/\\s+/g,' ').trim(); if(t && !seen[t]){ seen[t]=1; out.push(t); } };
  var box=document.getElementById('output_messages'); if(box) push(box.textContent);
  var sels=['.outputmsg_error','.outputmsg','.notification-error','.alert-danger','#status_messages'];
  for (var i=0;i<sels.length;i++){ var els=document.querySelectorAll(sels[i]); for (var j=0;j<els.length;j++) push(els[j].textContent); }
  return JSON.stringify({ok:true, banner: out.join(' | ')});
}catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;

function isCdpStale(err) {
  return /Session with given id not found|-32001|No target with given id|-32602|Target closed/i.test(String((err && err.message) || err));
}

function pageJson(raw) {
  if (raw && typeof raw === 'object') return raw;
  try { return JSON.parse(String(raw)); } catch (e) { return { ok: false, error: 'unparseable page result: ' + String(raw).slice(0, 200) }; }
}

// ─── ServiceNow transport ──────────────────────────────────────────────────────

function makeSn(io, opts) {
  const state = { tab: null, token: null, formTab: null, formSysId: null, formTabOwned: false };
  const log = opts.log || (() => {});

  /** Any logged-in tab on the host will do for /api/ calls: g_ck is per session, not per
   *  page. Form work goes through formTab(), which checks for g_form. */
  async function findTab(fresh) {
    if (state.tab && !fresh) return state.tab;
    if (!fresh) {
      const tabs = await io.listTabs();
      // Avoid the form tab: right after gsftSubmit it is reloading, so its g_ck is briefly
      // unreadable. Any other tab on the host carries the same session token.
      const formId = state.formTab && state.formTab.targetId;
      const onHost = tabs.filter((t) => t.url && t.url.includes(SN_HOST));
      const hit = onHost.find((t) => t.targetId !== formId) || onHost[0];
      if (hit) { state.tab = { targetId: hit.targetId, url: hit.url }; state.token = null; return state.tab; }
    }
    log(`opening a fresh ${SN_HOST} tab`);
    state.tab = await io.openTab(SN_ORIGIN + '/nav_to.do?uri=change_request_list.do');
    state.token = null;
    if (!state.tab || !state.tab.targetId) {
      cli.die(`No logged-in ${SN_HOST} tab, and a fresh one could not be opened.\n`
        + `Open ${SN_ORIGIN} in the browser, log in, then retry.`, { prefix: 'change' });
    }
    return state.tab;
  }

  /**
   * Read window.g_ck, retrying: a tab that is mid-navigation — which is exactly what a tab
   * looks like just after gsftSubmit — has no g_ck yet, and that is not a logged-out session.
   * The message stays weaker than «logged out» unless the URL proves it, and it never claims
   * the surrounding operation failed, because that is not known here.
   */
  async function token(tab, attempts) {
    if (state.token) return state.token;
    const tries = attempts || 5;
    let url = tab && tab.url;
    for (let i = 0; i < tries; i += 1) {
      if (i > 0) await io.sleep(500);
      let raw = null;
      try {
        raw = await io.pageEval(tab, JS_TOKEN);
      } catch (err) {
        if (isCdpStale(err)) throw err;
      }
      if (raw && typeof raw === 'string' && raw.length >= 8) { state.token = raw; return raw; }
      try { url = await io.pageEval(tab, 'location.href'); } catch (e) { /* keep the previous url */ }
      if (typeof url === 'string' && /login|sso|saml|okta|\/auth/i.test(url)) {
        throw new Error(`the ${SN_HOST} tab is on a sign-in page (${url.slice(0, 120)}) — log in in the browser and retry`);
      }
    }
    throw new Error(`could not read the session token (window.g_ck) on the ${SN_HOST} tab after `
      + `${tries} attempts, ${tries * 0.5}s apart. The page may still have been loading. `
      + 'This says nothing about whether the previous step took effect: check with '
      + '`change get <CHG…>` before retrying.');
  }

  /** One /api/ call in the page context. X-UserToken is mandatory: without it
   *  requests HANG instead of returning 401. */
  async function api(method, path, body, params) {
    let url = path;
    if (params) {
      const qs = Object.keys(params).filter((k) => params[k] !== undefined)
        .map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&');
      if (qs) url += (url.includes('?') ? '&' : '?') + qs;
    }
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const tab = await findTab(attempt > 0);
      try {
        const headers = { 'X-UserToken': await token(tab), Accept: 'application/json' };
        const init = { method, headers };
        if (body !== undefined) { init.body = JSON.stringify(body); headers['Content-Type'] = 'application/json'; }
        log(`${method} ${url}`);
        const res = await io.pageFetch(tab, url, init);
        return { status: res.status, body: res.body, ok: res.status >= 200 && res.status < 300 };
      } catch (err) {
        if (isCdpStale(err) && attempt === 0) { state.tab = null; state.token = null; continue; }
        throw err;
      }
    }
    throw new Error('unreachable');
  }

  /**
   * Poll a tab until its change form is scriptable. Returns the last probe either way.
   * ~9-10 s is normal, so the default budget is generous; the failure message says how
   * long it waited and what was still missing.
   */
  async function awaitFormReady(tab, sysId, budgetMs, pollMs) {
    const started = io.now().getTime();
    let probe = { ok: false, error: 'not probed' };
    let waited = 0;
    for (;;) {
      try {
        probe = pageJson(await io.pageEval(tab, jsFormState(sysId)));
      } catch (err) {
        if (isCdpStale(err)) return { probe: { ok: false, error: String(err.message || err) }, waited, stale: true };
        throw err;
      }
      if (probe.ok) return { probe, waited };
      waited = io.now().getTime() - started;
      if (waited >= budgetMs) return { probe, waited, timedOut: true };
      await io.sleep(pollMs);
    }
  }

  /**
   * A tab parked on the change form of one record, for g_form work.
   * Prefers a tab already showing this record's form — several ServiceNow tabs are usually
   * open and most of them are list views with no g_form, so matching on the host alone is
   * wrong. Only opens a tab when nothing matches, and remembers that it owns it so
   * closeFormTab() cleans up exactly what it created.
   */
  async function formTab(sysId, fresh) {
    const budget = (opts.formTimeout || DEFAULTS.formTimeout) * 1000;
    if (state.formTab && state.formSysId === sysId && !fresh) {
      const r = await awaitFormReady(state.formTab, sysId, budget, 500);
      if (r.probe.ok && r.probe.sysId === sysId) return state.formTab;
      state.formTab = null; state.formSysId = null;
    }

    // 1. an existing tab already on this record's form
    if (!fresh) {
      const tabs = await io.listTabs();
      const match = tabs.find((t) => t.url && t.url.includes('change_request.do') && t.url.includes(sysId));
      if (match) {
        const tab = { targetId: match.targetId, url: match.url };
        const r = await awaitFormReady(tab, sysId, budget, 500);
        if (r.probe.ok && r.probe.sysId === sysId) {
          log(`reusing tab ${match.targetId} on ${sysId} (ready after ${r.waited} ms)`);
          state.formTab = tab; state.formSysId = sysId; state.formTabOwned = false;
          return tab;
        }
        log(c.dim(`existing tab ${match.targetId} is not usable (${r.probe.error}), opening a fresh one`));
      }
    }

    // 2. open one, and own it
    const url = `${SN_ORIGIN}/change_request.do?sys_id=${encodeURIComponent(sysId)}`
      + `&sysparm_view=${FORM_VIEW}&sysparm_view_forced=true`;
    const tab = await io.openTab(url);
    if (!tab || !tab.targetId) throw new Error('could not open a change form tab for ' + sysId);
    state.formTab = tab; state.formSysId = sysId; state.formTabOwned = true;
    const r = await awaitFormReady(tab, sysId, budget, 500);
    if (!r.probe.ok) {
      throw new Error(`the change form for ${sysId} was not scriptable after ${Math.round(r.waited / 1000)}s`
        + ` (${r.probe.error || 'unknown reason'}; g_form=${r.probe.hasForm} gsftSubmit=${r.probe.hasSubmit}`
        + ` readyState=${r.probe.readyState}). Raise --form-timeout if this instance is slower than 30s.`);
    }
    if (r.probe.sysId && r.probe.sysId !== sysId) throw new Error(`change form loaded the wrong record: ${r.probe.sysId}`);
    // A form that takes seconds is worth telling the user about: the alternative is a
    // silent multi-second pause in the middle of a change.
    if (r.waited >= 1000) console.error(c.dim(`  form became scriptable after ${r.waited} ms`));
    else if (r.waited) log(c.dim(`  form became scriptable after ${r.waited} ms`));
    return tab;
  }

  async function banner() {
    if (!state.formTab) return '';
    try { return (pageJson(await io.pageEval(state.formTab, JS_BANNER)).banner || ''); } catch (e) { return ''; }
  }

  /** Close the form tab only if this process opened it: a tab the human had open stays. */
  async function closeFormTab() {
    if (state.formTab && state.formTabOwned && io.closeTab) {
      try { await io.closeTab(state.formTab.targetId); } catch (e) { /* best effort */ }
    }
    state.formTab = null; state.formSysId = null; state.formTabOwned = false;
  }

  return { findTab, token, api, formTab, banner, closeFormTab, _state: state };
}

// ─── ServiceNow operations ─────────────────────────────────────────────────────

function apiError(what, res) {
  let detail = '';
  const b = res && res.body;
  if (b && b.error) detail = [b.error.message, b.error.detail].filter(Boolean).join(' — ');
  else if (typeof b === 'string') detail = b.slice(0, 300);
  else if (b) detail = JSON.stringify(b).slice(0, 300);
  return new Error(`${what} failed: HTTP ${res && res.status}${detail ? ' — ' + detail : ''}`);
}

const REF = (v) => (v && typeof v === 'object' ? (v.value || '') : (v == null ? '' : String(v)));

/** Read a change with raw (non-display) values, which is the only trustworthy read. */
async function readChange(sn, sysId, fields) {
  const res = await sn.api('GET', `/api/now/table/change_request/${sysId}`, undefined, {
    sysparm_display_value: 'false',
    sysparm_fields: fields ? fields.join(',') : undefined,
  });
  if (!res.ok) throw apiError('reading the change', res);
  return (res.body && res.body.result) || {};
}

async function resolveSysId(sn, ident) {
  if (/^[0-9a-f]{32}$/i.test(ident)) return ident;
  if (!/^CHG\d+$/i.test(ident)) {
    cli.die(`Not a CHG number or sys_id: ${ident}`, { prefix: 'change' });
  }
  const res = await sn.api('GET', '/api/now/table/change_request', undefined, {
    sysparm_query: 'number=' + ident.toUpperCase(),
    sysparm_limit: '1',
    sysparm_display_value: 'false',
    sysparm_fields: 'sys_id,number',
  });
  if (!res.ok) throw apiError('looking up ' + ident, res);
  const rows = (res.body && res.body.result) || [];
  if (!rows.length) cli.die(`No change ${ident.toUpperCase()} is visible to this account.`, { prefix: 'change' });
  return rows[0].sys_id;
}

function buildCreatePayload(o, start, end) {
  const payload = {
    short_description: o.title,
    description: o.description,
    type: o.type,
    category: o.category,
    chg_model: o.chgModel,
    cmdb_ci: o.ci,
    u_service_offering_instance: o.instance,
    u_hosting_location: HOSTING_LOCATIONS[o.hostingLocation] || o.hostingLocation,
    u_tenant_type: o.tenantType,
    u_environment: o.environment,
    u_customer_impact: o.customerImpact,
    u_change_complexity: o.complexity,
    u_cr_reason_justification: o.reason,
    u_backout_plan_type: o.backoutType,
    u_production_validation_testing_method: o.validation,
    u_change_fixing_cso: o.cso,
    u_change_target_type: o.targetType,
    u_classification: o.classification,
    u_documentation: o.documentation,
    // u_risk_type is SILENTLY DROPPED on create (the instance derives it from service +
    // risk through the populateRiskType GlideAjax). Sent anyway for forward
    // compatibility; step 3's PATCH /api/sn_chg_rest/change/{sys_id} is what makes it
    // stick. u_environment is dropped on create for the same reason and re-sent there.
    u_risk_type: o.riskType,
    risk: o.risk,
    impact: o.snImpact,
    urgency: o.urgency,
    scope: o.scope,
    cab_required: 'false',
    u_change_deployer: o.deployer,
    requested_by: o.requestedBy,
    u_submitter: o.submitter,
    u_change_approver: o.approver,
    implementation_plan: o.implementationPlan,
    backout_plan: o.backoutPlan,
    test_plan: o.testPlan,
    justification: o.justification,
    risk_impact_analysis: o.riskAnalysis,
  };
  // The planned window is accepted on create (that is how CHG005367969 got its window),
  // so send it here and treat the calendar PATCH as a follow-up rather than the only path.
  if (start && end) {
    payload.start_date = snDate(start);
    payload.end_date = snDate(end);
  }
  return payload;
}

async function createChange(sn, o, log, start, end) {
  const payload = buildCreatePayload(o, start, end);
  const res = await sn.api('POST', '/api/now/table/change_request', payload, { sysparm_display_value: 'false' });
  if (!res.ok) throw apiError('creating the change', res);
  const r = (res.body && res.body.result) || {};
  const sysId = REF(r.sys_id);
  const number = REF(r.number);
  if (!sysId || !number) throw new Error('create returned HTTP ' + res.status + ' but no sys_id/number: ' + JSON.stringify(res.body).slice(0, 300));

  // Never trust the create response: read the record back raw and check the
  // fields that matter actually landed.
  const check = await readChange(sn, sysId, ['number', 'state', 'short_description', 'cmdb_ci',
    'u_service_offering_instance', 'u_hosting_location', 'u_environment', 'u_change_approver',
    'u_change_fixing_cso']);
  const mismatch = [];
  if (REF(check.short_description) !== o.title) mismatch.push('short_description');
  if (REF(check.cmdb_ci) !== o.ci) mismatch.push('cmdb_ci');
  if (REF(check.u_service_offering_instance) !== o.instance) mismatch.push('u_service_offering_instance');
  const wantLoc = HOSTING_LOCATIONS[o.hostingLocation] || o.hostingLocation;
  if (REF(check.u_hosting_location) !== wantLoc) mismatch.push('u_hosting_location');
  if (REF(check.u_change_fixing_cso) !== o.cso) mismatch.push('u_change_fixing_cso');
  if (mismatch.length) {
    log(c.yellow(`warning: the server did not store ${mismatch.join(', ')} as sent`));
  }
  return { sysId, number, state: REF(check.state), mismatch };
}

/**
 * Confirm the planned window is on the record. The write is not the problem — the read is:
 * a single read straight after the calendar PATCH can still show the old (empty) values,
 * which is a false negative that used to abort a perfectly good change. So poll.
 */
async function readWindow(sn, io, sysId, attempts, delayMs) {
  let check = {};
  for (let i = 0; i < attempts; i += 1) {
    if (i > 0) await io.sleep(delayMs);
    check = await readChange(sn, sysId, ['start_date', 'end_date']);
    if (REF(check.start_date) && REF(check.end_date)) {
      return { ok: true, start_date: REF(check.start_date), end_date: REF(check.end_date), attempts: i + 1 };
    }
  }
  return { ok: false, attempts };
}

/**
 * The window is already in the create payload. This re-asserts it (and is the only path for
 * an existing change), tolerating the read-after-write race, then falls back to the plain
 * table API before giving up. All three endpoints are known to accept these two fields.
 */
/**
 * Confirm the planned window that the create payload carried, and repair it over the table
 * API if it is missing.
 *
 * DO NOT reintroduce PATCH /api/now/change_request_calendar/change_request/{sys_id} here.
 * Despite its {start_date, end_date} body, on this instance that endpoint writes
 * work_start / work_end — the ACTUAL work times — and leaves the planned window empty.
 * Proven from sys_history_line on CHG005368567: update #1, the only call between create and
 * the next read, set work_start/work_end to the create-time window. Two further consequences
 * followed from those stray actuals: the model auto-advanced Assess → Review, and it
 * reclassified the change as «Latent (Violated)» because work had apparently begun before the
 * planned start. See references/servicenow.md.
 */
async function setWindow(sn, io, sysId, start, end, log) {
  const body = { start_date: snDate(start), end_date: snDate(end) };

  // The window is part of the create payload, so normally it is already there. Allow for a
  // lagging read before concluding otherwise.
  let win = await readWindow(sn, io, sysId, 3, 1000);
  if (win.ok) return { start_date: win.start_date, end_date: win.end_date, via: 'create' };

  log(c.yellow('  the planned window is not on the record, setting it through the table API.'
    + ' Note: a change created without a future planned start is reclassified type=latent at its'
    + ' first hop, and editing the window afterwards does not undo that (CHG005368783).'));
  const fb = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`, body,
    { sysparm_display_value: 'false', sysparm_fields: 'start_date,end_date' });
  if (!fb.ok) throw apiError('setting the planned window', fb);
  win = await readWindow(sn, io, sysId, 3, 1000);
  if (win.ok) return { start_date: win.start_date, end_date: win.end_date, via: 'table' };

  throw new Error('the planned window is still empty after the create payload, three verifying '
    + 'reads a second apart, a table-API PATCH and three more reads. '
    + `Wanted ${body.start_date} → ${body.end_date} (UTC).`);
}

async function setChgRest(sn, sysId, fields) {
  const res = await sn.api('PATCH', `/api/sn_chg_rest/change/${sysId}`, fields);
  if (!res.ok) throw apiError('PATCH /api/sn_chg_rest/change', res);
  const keys = Object.keys(fields);
  const check = await readChange(sn, sysId, keys);
  const bad = keys.filter((k) => REF(check[k]) !== fields[k]);
  return { ok: !bad.length, bad, stored: check };
}

async function nextStates(sn, sysId) {
  const res = await sn.api('GET', `/api/sn_chg_rest/change/${sysId}/nextstates`);
  if (!res.ok) throw apiError('reading nextstates', res);
  const r = (res.body && res.body.result) || {};
  return {
    available: r.available_states || [],
    labels: r.state_label || {},
    transitions: [].concat.apply([], r.state_transitions || []),
  };
}

// Fields the form validates before it will make a hop, with the flag that sets each one on
// create. Checked before submitting, so a missing field is an instruction rather than a
// banner nobody can act on.
const BASE_REQUIREMENTS = [
  { field: 'u_service_offering_instance', label: 'Instance(s)', flag: '--instance=<sys_id>', glide: true },
  { field: 'u_change_fixing_cso', label: 'Change is related to an emergency', flag: '--cso=none|fix|prevent' },
  { field: 'u_tenant_type', label: 'Tenant type', flag: '--tenant-type=Multi|Single' },
];

const HOP_REQUIREMENTS = {
  '-4': [],
  '-1': [
    { field: 'u_change_approver', label: 'Change approver', flag: '--approver=<sys_id>', glide: true },
  ],
  // work_start/work_end are ACTUAL execution times. `change run` measures them; a bare hop can
  // only accept them from --work-start/--work-end, and never invents them.
  '0': [
    { field: 'work_start', label: 'Actual start', flag: '--work-start=<UTC datetime>', actual: true },
    { field: 'work_end', label: 'Actual end', flag: '--work-end=<UTC datetime>', actual: true },
  ],
  3: [
    { field: 'close_code', label: 'Close code', flag: '--close-code=successful' },
    { field: 'u_impact_minutes', label: 'Impact minutes', flag: '--impact-minutes=0' },
  ],
};

/**
 * What to tell someone whose hop is blocked. Every suggestion has to be a command that can
 * actually succeed — the same rule that retired the old «set it with --instance» hint.
 */
function hopAdvice(missing, ident) {
  const actuals = missing.filter((m) => m.actual);
  const config = missing.filter((m) => !m.actual);
  const out = [];
  if (config.length) {
    out.push('On an existing change the field flags alone do not help: they are read when a change'
      + ' is CREATED. Repair the record first, either of:');
    out.push(`  change repair ${ident} --confirm`);
    out.push(`  change --repair --confirm <hop> ${ident}   (repairs only what the hop needs)`);
    // Only ever name real flags, and only when there are any.
    const flags = config.map((m) => m.flag).filter((f) => f && f.startsWith('--'));
    if (flags.length) out.push(`  add ${flags.join(' ')} to either of the above to override the defaults`);
  }
  if (actuals.length) {
    if (config.length) out.push('');
    out.push(`${actuals.map((m) => m.field).join(' and ')} ${actuals.length > 1 ? 'are' : 'is'} the ACTUAL`
      + ' execution window, so nothing can invent them. Either:');
    out.push('  change --confirm run <command>          measures the real window (the normal path)');
    out.push(`  change --work-start="YYYY-MM-DD HH:MM:SS" --work-end="…" --confirm review ${ident}`);
    out.push('                                         hand-supplied, disclosed on the ticket');
    out.push('The repair subcommand cannot fill them: it fills configuration fields, and fabricating actuals'
      + ' would falsify the audit trail.');
  }
  return '\n' + out.join('\n');
}

/** Everything the form validates for this hop: the always-mandatory set plus the hop's own. */
function requirementsFor(code) {
  const seen = {};
  const out = [];
  for (const r of BASE_REQUIREMENTS.concat(HOP_REQUIREMENTS[code] || [])) {
    if (!seen[r.field]) { seen[r.field] = true; out.push(r); }
  }
  return out;
}

/** Which required fields are empty on the record for this hop. */
async function missingForHop(sn, sysId, code) {
  const req = requirementsFor(code);
  if (!req.length) return [];
  const rec = await readChange(sn, sysId, req.map((r) => r.field));
  return req.filter((r) => !REF(rec[r.field]));
}

// The banner names a LABEL; map it back to the field so the fix is unambiguous.
function hintForBanner(banner) {
  if (!banner) return '';
  const hits = [];
  for (const code of Object.keys(HOP_REQUIREMENTS).concat(['*'])) {
    for (const r of (code === '*' ? BASE_REQUIREMENTS : HOP_REQUIREMENTS[code])) {
      if (banner.toLowerCase().includes(r.label.toLowerCase())) hits.push(r.field);
    }
  }
  if (!hits.length) return '';
  return `\nThat maps to ${hits.join(', ')} on the record.`
    + ' If the record itself is empty there (an older client can blank it), repair it first:'
    + ` change repair <CHG…> --confirm, or pass --repair to this command.`;
}

// The lifecycle in order, so «it moved further than asked» can be told apart from «it did not
// move». Canceled (4) is off to the side and deliberately absent.
const LIFECYCLE = ['-5', '-4', '-3', '-2', '-1', '0', '3'];

/**
 * Refuse a hop whose form-mandatory fields are empty on the RECORD, or — with --repair — fill
 * them first. `only` limits the check, which `close` uses to look at the always-mandatory set
 * before it writes close_code.
 */
async function preflightHop(sn, io, sysId, code, o, cur, only) {
  const log = o.log || (() => {});
  const label = STATE[code] || code;
  const scope = (list) => (only ? list.filter((m) => only.includes(m.field)) : list);
  let missing = scope(await missingForHop(sn, sysId, code));
  // Hand-supplied actuals need no --repair: giving the values IS the intent. They are written
  // verbatim (never floored, never invented) and disclosed in the work notes.
  const actualsMissing = missing.filter((m) => m.actual);
  if (actualsMissing.length && o.resolved && o.resolved.workStart && o.resolved.workEnd) {
    const ws = o.resolved.workStart;
    const we = o.resolved.workEnd;
    log(c.yellow(`  writing hand-supplied actuals ${snDate(ws)} → ${snDate(we)} (UTC)`));
    await setWorkTimes(sn, sysId, ws, we, log, { verbatim: true });
    try {
      await postWorkNotes(sn, sysId,
        ['Actual execution window supplied by hand, not measured by the change wrapper.',
          `work_start: ${snDate(ws)} UTC`,
          `work_end: ${snDate(we)} UTC`,
          'Recorded via --work-start/--work-end on a bare state transition, so no command output'
          + ' accompanies this window.'].join('\n'),
        'Actual execution window supplied by hand');
    } catch (err) {
      throw new Error('the actuals were written but the disclosing work note failed: ' + err.message
        + '. Undisclosed hand-supplied actuals are not acceptable, so the hop was not attempted.');
    }
    missing = scope(await missingForHop(sn, sysId, code));
  }
  if (missing.length && !o.relaxMandatory && o.repair && o.resolved) {
    // --repair: fill exactly what this hop needs, then re-check. Same rules as `change repair`.
    log(c.yellow(`  --repair: ${missing.map((m) => m.field).join(', ')} `
      + `${missing.length > 1 ? 'are' : 'is'} empty on the record, filling from flags/defaults`));
    await repairChange(sn, sysId, o.resolved, {
      only: missing.map((m) => m.field), forced: o.forced || [], confirm: true, log,
    });
    missing = scope(await missingForHop(sn, sysId, code));
  }
  if (missing.length && !o.relaxMandatory) {
    const ident = o.ident || sysId;
    throw new Error(`${STATE[cur] || cur} → ${label} needs ${missing.map((m) => m.field).join(', ')},`
      + ` which ${missing.length > 1 ? 'are' : 'is'} empty on this change.\n`
      + missing.map((m) => `  ${m.field} («${m.label}»)`).join('\n')
      + (o.repair
        ? '\n--repair was given, but the value is not configured either: pass '
          + missing.map((m) => m.flag).join(' and ') + ' alongside --repair.'
        : hopAdvice(missing, ident)));
  }


}

/**
 * Move a change to `code` through the form, then prove it moved by re-reading
 * the record. HTTP 200 from the form submit means nothing.
 */
async function transition(sn, io, sysId, code, opts) {
  const o = opts || {};
  const log = o.log || (() => {});
  const label = STATE[code] || code;
  const cur = REF((await readChange(sn, sysId, ['state'])).state);
  if (cur === code) return { state: code, moved: false };

  if (!o.skipNextStates) {
    const ns = await nextStates(sn, sysId);
    if (ns.available.length && !ns.available.includes(code)) {
      const failed = ns.transitions
        .filter((t) => t && t.to_state === code)
        .map((t) => (t.conditions || []).filter((x) => x && x.passed === false)
          .map((x) => (x.condition && x.condition.name) || 'unnamed condition').join(', '))
        .filter(Boolean).join('; ');
      throw new Error(`${STATE[cur] || cur} → ${label} is not available.`
        + ` Available: ${ns.available.map((s) => `${s} (${ns.labels[s] || STATE[s] || '?'})`).join(', ')}`
        + (failed ? `. Unmet conditions: ${failed}` : ''));
    }
  }

  await preflightHop(sn, io, sysId, code, o, cur);

  // Reconcile the WHOLE tracked field set, not just what this hop requires: the submit posts
  // every widget, so an empty one blanks a populated record field. This runs for cancels too —
  // relaxing mandatory validation is not a licence to destroy data.
  const recon = await reconcileForm(sn, io, sysId, log);

  // Then the strict, per-requirement check on the glide_list fields this hop validates.
  if (!o.relaxMandatory) await hydrateFormGlideLists(sn, io, sysId, code, log);

  const tab = await sn.formTab(sysId);
  const submit = pageJson(await io.pageEval(tab, jsSetState(code, !!o.relaxMandatory)));
  if (!submit.ok) throw new Error(`could not submit ${label} on the change form: ${submit.error}`);

  const deadline = io.now().getTime() + (o.timeout || DEFAULTS.stateTimeout) * 1000;
  let last = cur;
  let reached = null;
  while (io.now().getTime() < deadline) {
    await io.sleep(o.pollMs || 2000);
    last = REF((await readChange(sn, sysId, ['state'])).state);
    if (last === code) { reached = { state: code, moved: true }; break; }
    // The Adobe model has automatic transitions (Assess → Review fires by itself once its
    // conditions pass), so the state can legitimately end up FURTHER along than asked.
    // That is not a failure, but it must be reported, not hidden.
    const want = LIFECYCLE.indexOf(code);
    const got = LIFECYCLE.indexOf(last);
    if (want >= 0 && got > want) { reached = { state: last, moved: true, overshot: true, asked: code }; break; }
  }

  // Whether or not the state moved, check what the submit did to the record's fields.
  const checkOpts = { expectType: o.expectType, type: o.expectType, leadTime: o.leadTime,
    when: `after the ${label} hop` };
  if (reached) {
    await assertNoBlanking(sn, sysId, recon.snapshot, checkOpts);
    return reached;
  }

  let blanked = '';
  try { await assertNoBlanking(sn, sysId, recon.snapshot, checkOpts); } catch (err) { blanked = `\n${err.message}`; }
  const banner = await sn.banner();
  throw new Error(`the change did not move to ${label}: state is still ${STATE[last] || last}.`
    + (banner ? `\nServiceNow said: ${banner}${hintForBanner(banner)}` : '\nNo banner text was found on the form.')
    + blanked);
}

// Every field the wrapper sets at create, i.e. everything a form submit could blank.
// `gsftSubmit` posts the WHOLE form, so any widget that is empty in the DOM is written back as
// empty — which silently cleared u_service_offering_instance, u_environment and
// u_hosting_location on CHG005368815 during the New → Assess hop, a hop that requires none of
// them. So reconciliation is not per-hop-requirement: it runs before every submit.
const TRACKED_FIELDS = [
  { field: 'u_service_offering_instance', glide: true },
  { field: 'u_change_approver', glide: true },
  { field: 'u_hosting_location' },
  { field: 'u_environment' },
  { field: 'u_tenant_type' },
  { field: 'cmdb_ci' },
  { field: 'u_change_fixing_cso' },
];
const TRACKED_NAMES = TRACKED_FIELDS.map((f) => f.field);

/**
 * One page call that compares every tracked field's widget against the record and fills the
 * widget in where it is empty and the record is not.
 *
 * glide_list widgets ignore g_form.setValue, plain fields and references do not, so both are
 * attempted: g_form first (it keeps the form's own model consistent) and then the DOM nodes.
 */
function jsReconcileFields(spec) {
  return `(function(){try{
    var spec = ${JSON.stringify(spec)}, out = [];
    var read = function(f, hidden){
      var v = null;
      if (typeof g_form !== 'undefined') { try { v = g_form.getValue(f); } catch(e) { v = null; } }
      if (v === null || v === undefined || v === '') { if (hidden && hidden.value) v = hidden.value; }
      return (v === null || v === undefined) ? '' : String(v);
    };
    for (var i = 0; i < spec.length; i++) {
      var s = spec[i], f = s.field;
      var hidden = document.getElementById('change_request.' + f);
      var mirror = document.getElementById('sys_display.change_request.' + f);
      var sel = document.getElementById('select_0change_request.' + f);
      var present = !!hidden;
      if (!present && typeof g_form !== 'undefined' && g_form.getControl) {
        try { present = !!g_form.getControl(f); } catch(e) { present = false; }
      }
      var before = read(f, hidden), action = present ? 'left' : 'absent';
      if (present && before === '' && s.value) {
        if (typeof g_form !== 'undefined') {
          try { s.display ? g_form.setValue(f, s.value, s.display) : g_form.setValue(f, s.value); } catch(e) {}
        }
        if (hidden && !hidden.value) hidden.value = s.value;
        if (mirror && !mirror.value) mirror.value = s.display || s.value;
        if (sel) {
          var has = false;
          for (var k = 0; k < sel.options.length; k++) { if (sel.options[k].value === s.value) { has = true; sel.options[k].selected = true; } }
          if (!has) { var o = document.createElement('option'); o.value = s.value; o.text = s.display || s.value; o.selected = true; sel.appendChild(o); }
        }
        if (hidden) { try { hidden.dispatchEvent(new Event('change', { bubbles: true })); } catch(e) {} }
        action = 'reconciled';
      }
      out.push({ field: f, before: before, after: read(f, hidden), action: action, present: present, node: !!hidden });
    }
    return JSON.stringify({ ok: true, fields: out });
  }catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()`;
}

/** Reconcile the form against the record, and snapshot the record for the blanking check. */
async function reconcileForm(sn, io, sysId, log) {
  const raw = await readChange(sn, sysId, TRACKED_NAMES);
  const disp = await readChangeDisplay(sn, sysId, TRACKED_NAMES);
  const snapshot = {};
  for (const n of TRACKED_NAMES) snapshot[n] = REF(raw[n]);
  const spec = TRACKED_FIELDS.map((f) => ({
    field: f.field, value: snapshot[f.field], display: REF(disp[f.field]) || undefined, glide: !!f.glide,
  })).filter((x) => x.value);
  if (!spec.length) return { snapshot, fields: [] };
  const tab = await sn.formTab(sysId);
  const res = pageJson(await io.pageEval(tab, jsReconcileFields(spec)));
  if (!res.ok) throw new Error(`could not reconcile the form against the record: ${res.error}`);
  const done = (res.fields || []).filter((f) => f.action === 'reconciled');
  if (done.length) {
    log(c.dim(`  reconciled ${done.length} form field${done.length > 1 ? 's' : ''} from the record: `
      + done.map((f) => f.field).join(', ')));
  }
  const want = {};
  for (const x of spec) want[x.field] = x.value;
  // A field the form does not render is not posted by the submit, so it is safe: only a field
  // that IS on the form and still empty would be written back as empty.
  const absent = (res.fields || []).filter((f) => f.action === 'absent');
  if (absent.length) {
    log(c.dim(`  not on this form, so not at risk: ${absent.map((f) => f.field).join(', ')}`));
  }
  const stuck = (res.fields || []).filter((f) => want[f.field] && f.present && !f.after);
  if (stuck.length) {
    throw new Error(`the form would not accept ${stuck.map((f) => f.field).join(', ')}`
      + ' — submitting now would blank ' + (stuck.length > 1 ? 'those fields' : 'that field')
      + ' on the record, so nothing was submitted.');
  }
  return { snapshot, fields: res.fields || [] };
}

/**
 * A submit that blanks a field the record had is data loss on a production record, so it is
 * reported and the run stops. It is deliberately not retried: a retry would submit the same
 * incomplete form again.
 */
async function assertNoBlanking(sn, sysId, snapshot, opts) {
  const o = opts || {};
  const after = await readChange(sn, sysId, ['type', 'start_date'].concat(TRACKED_NAMES));
  const blanked = TRACKED_NAMES.filter((n) => snapshot[n] && !REF(after[n]));
  // A latent reclassification clears those same fields, so check the cause before blaming the
  // submit: reporting «the submit blanked three fields» when the model reclassified the change
  // would be a confident message pointing at the wrong thing.
  if (o.expectType && REF(after.type) !== o.expectType) {
    throw latentError(REF(after.type), REF(after.start_date), o, o.when || 'after the form submit',
      blanked.length ? `The same update cleared ${blanked.join(', ')}.` : undefined);
  }
  if (!blanked.length) return [];
  throw new Error(`the form submit blanked ${blanked.length} field${blanked.length > 1 ? 's' : ''} `
    + `the record previously held: ${blanked.map((n) => `${n} was ${JSON.stringify(snapshot[n])}`).join(', ')}.\n`
    + 'gsftSubmit posts the whole form, so a widget that is empty in the DOM is written back as'
    + ' empty. This is data loss on a production record, not a transient error, so it is not'
    + ' retried. Restore the values above before using this change.');
}

/** Read a change with display values, for the labels a glide_list mirror should show. */
async function readChangeDisplay(sn, sysId, fields) {
  const res = await sn.api('GET', `/api/now/table/change_request/${sysId}`, undefined, {
    sysparm_display_value: 'true', sysparm_fields: fields.join(','),
  });
  return (res.ok && res.body && res.body.result) || {};
}

/**
 * Hydrate the FORM from the RECORD for every glide_list field the hop requires.
 *
 * A value written through the Table API does not reach a glide_list widget: the record has it,
 * the form does not, and the form is what validates the hop. Proven twice on live records —
 * `u_change_approver` on CHG005368567 and `u_service_offering_instance` on CHG005368738, where
 * `g_form.getMissingFields()` returned `["u_service_offering_instance"]` while a raw read
 * showed `d0ae449893ffce50b7aef1e01bba1049`.
 *
 * So this is field-agnostic: whatever the hop needs and the form lacks gets written into the
 * hidden input, the sys_display mirror and the selected <option>, then read back.
 */
async function hydrateFormGlideLists(sn, io, sysId, code, log) {
  const reqs = requirementsFor(code).filter((r) => r.glide);
  if (!reqs.length) return [];
  const names = reqs.map((r) => r.field);
  const raw = await readChange(sn, sysId, names);
  const disp = await readChangeDisplay(sn, sysId, names);
  const tab = await sn.formTab(sysId);
  const actions = [];
  for (const r of reqs) {
    const value = REF(raw[r.field]);
    const probe = pageJson(await io.pageEval(tab, jsReadGlideList(r.field)));
    if (!probe.ok) { actions.push({ field: r.field, skipped: probe.error }); continue; }
    if (probe.value) { actions.push({ field: r.field, alreadyOnForm: true }); continue; }
    if (!value) { actions.push({ field: r.field, missingOnRecord: true }); continue; }
    const label = REF(disp[r.field]) || value;
    log(c.dim(`  hydrating ${r.field} on the form from the record (${label})`));
    const set = pageJson(await io.pageEval(tab, jsSetGlideList(r.field, value, label)));
    if (!set.ok) throw new Error(`could not write ${r.field} into the form: ${set.error}`);
    const after = pageJson(await io.pageEval(tab, jsReadGlideList(r.field)));
    if (!after.ok || !after.value) {
      throw new Error(`${r.field} is still empty on the form after writing its glide_list nodes`
        + ` (hidden input change_request.${r.field}). The record has ${value}.`);
    }
    actions.push({ field: r.field, hydrated: true, value });
  }
  return actions;
}

/**
 * Read `type` back and refuse anything but the requested one.
 *
 * A change whose planned start is not in the future is reclassified `type=latent` by a
 * business rule, and latent changes run a DIFFERENT state machine: Assess → Implement is
 * refused («Change model 'Adobe Change Model' prevented state transition from Assess to
 * Implement» plus «Invalid update»), Assess → Review fires automatically, and
 * u_hosting_location / u_environment / u_service_offering_instance come back empty even though
 * the create payload carried them. Diagnosed by diffing CHG005367969 (standard, worked) against
 * CHG005368783 (latent, refused).
 */
function latentError(type, storedStart, o, when, extra) {
  return new Error(`the record's \`type\` reads ${JSON.stringify(type)} ${when}`
    + ` (read with sysparm_display_value=false), not ${JSON.stringify(o.type)}.\n`
    + 'ServiceNow reclassifies a change whose planned start is not in the future as a latent'
    + ' (retroactive) change, and the latent path is a different state machine: Assess → Implement'
    + ' is refused, Assess → Review fires by itself, and u_hosting_location, u_environment and'
    + ' u_service_offering_instance come back empty.\n'
    + `Planned start as stored: ${storedStart || '(empty)'}. `
    + `Raise --lead-time (currently ${o.leadTime}s) so the window starts in the future.`
    + (extra ? '\n' + extra : ''));
}

async function assertRequestedType(sn, sysId, o, when) {
  const rec = await readChange(sn, sysId, ['type', 'start_date']);
  const type = REF(rec.type);
  if (type === o.type) return type;
  throw latentError(type, REF(rec.start_date), o, when);
}

/** The most distinctive line of a note, for substring-matching it back out of the journal. */
function noteNeedle(text, preferred) {
  if (preferred && preferred.length > 3) return preferred.slice(0, 120);
  const lines = String(text).split('\n').map((l) => l.trim()).filter((l) => l.length > 3);
  return lines.sort((a, b) => b.length - a.length)[0] || String(text).slice(0, 60);
}

/**
 * Append a work note and prove it landed.
 *
 * Journal fields are readable ONLY through a display read of the parent record: a raw read of
 * `work_notes` returns an empty string, and `sys_journal_field` is ACL-blocked for this account
 * (a query by element_id returns []). The display read returns the whole journal, newest first,
 * each entry prefixed «MM-DD-YYYY HH:MM:SS - <user> (Work notes)».
 */
async function postWorkNotes(sn, sysId, text, preferredNeedle) {
  const res = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`, { work_notes: text },
    { sysparm_display_value: 'false', sysparm_fields: 'sys_id' });
  if (!res.ok) throw apiError('posting work notes', res);
  const check = await sn.api('GET', '/api/now/table/change_request', undefined, {
    sysparm_query: `sys_id=${sysId}`, sysparm_limit: '1',
    sysparm_display_value: 'true', sysparm_fields: 'work_notes',
  });
  const rows = (check.ok && check.body && check.body.result) || [];
  const journal = rows.length ? REF(rows[0].work_notes) : '';
  const needle = noteNeedle(text, preferredNeedle);
  return { verified: journal.includes(needle), needle, journal: journal.slice(0, 200) };
}

/** work_start/work_end: try the internal UTC format first, fall back to the
 *  account's display format (MM-DD-YYYY HH:MM:SS) with input_display_value. */
async function setWorkTimes(sn, sysId, start, realEnd, log, opts) {
  // A command that finishes inside a second would otherwise record work_start == work_end,
  // which reads like a bug and gives reports a zero-length window. The true duration is in the
  // work notes; this only floors what the record shows.
  const MIN_ACTUAL_MS = 60000;
  let end = realEnd;
  if (opts && opts.verbatim) {
    if (end.getTime() - start.getTime() < 1000) {
      log(c.yellow('  the supplied actual window is under a second: the record will show it as given,'
        + ' because hand-supplied values are never adjusted'));
    }
  } else if (end.getTime() - start.getTime() < MIN_ACTUAL_MS) {
    end = new Date(start.getTime() + MIN_ACTUAL_MS);
    log(c.dim(`  actual window floored to ${MIN_ACTUAL_MS / 1000}s on the record`
      + ` (real duration ${Math.round((realEnd.getTime() - start.getTime()) / 1000)}s is in the work notes)`));
  }
  let res = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`,
    { work_start: snDate(start), work_end: snDate(end) },
    { sysparm_display_value: 'false', sysparm_fields: 'work_start,work_end' });
  if (res.ok) {
    const check = await readChange(sn, sysId, ['work_start', 'work_end']);
    if (REF(check.work_start) && REF(check.work_end)) return { work_start: REF(check.work_start), work_end: REF(check.work_end), format: 'internal' };
  }
  log(c.yellow('work_start/work_end did not stick in internal format, retrying in display format'));
  res = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`,
    { work_start: snDisplayDate(start), work_end: snDisplayDate(end) },
    { sysparm_input_display_value: 'true', sysparm_display_value: 'false', sysparm_fields: 'work_start,work_end' });
  if (!res.ok) throw apiError('setting work_start/work_end', res);
  const check = await readChange(sn, sysId, ['work_start', 'work_end']);
  if (!REF(check.work_start) || !REF(check.work_end)) throw new Error('work_start/work_end are still empty after both PATCH attempts');
  return { work_start: REF(check.work_start), work_end: REF(check.work_end), format: 'display' };
}

async function closeChange(sn, io, sysId, o) {
  // Check (and optionally repair) what the form always demands before writing anything.
  const cur = REF((await readChange(sn, sysId, ['state'])).state);
  await preflightHop(sn, io, sysId, S_CLOSED, o, cur, BASE_REQUIREMENTS.map((r) => r.field));
  const fields = {
    close_code: o.closeCode || DEFAULTS.closeCode,
    close_notes: o.closeNotes || 'Closed by `change`.',
    u_impact_minutes: String(o.impactMinutes === undefined ? DEFAULTS.impactMinutes : o.impactMinutes),
  };
  const res = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`, fields,
    { sysparm_display_value: 'false', sysparm_fields: 'close_code,close_notes,u_impact_minutes' });
  if (!res.ok) throw apiError('setting the close fields', res);
  const check = await readChange(sn, sysId, ['close_code', 'u_impact_minutes']);
  if (REF(check.close_code) !== fields.close_code) {
    throw new Error(`close_code did not stick: wanted ${fields.close_code}, server has ${JSON.stringify(REF(check.close_code))}`);
  }
  return transition(sn, io, sysId, S_CLOSED, o);
}

async function cancelChange(sn, io, sysId, o) {
  // Cancelling from Implement trips mandatory-field validation on the form, so
  // g_form.checkMandatory has to be switched off before the state is set.
  return transition(sn, io, sysId, S_CANCELED, Object.assign({ relaxMandatory: true }, o || {}));
}

// ─── iPaaS transport (headless, unproven — no credentials exist yet) ───────────

function ipaasCreds(resolve, env) {
  const missing = [];
  const pick = (flag, cfg, envKey) => {
    const v = resolve(flag, cfg, envKey, undefined);
    if (!v || v === true) missing.push(envKey);
    return typeof v === 'string' ? v : undefined;
  };
  const creds = {
    apiKey: pick('api-key', 'ipaasApiKey', 'IPAAS_API_KEY'),
    clientId: pick('ims-client-id', 'imsClientId', 'IPAAS_IMS_CLIENT_ID'),
    clientSecret: pick('ims-client-secret', null, 'IPAAS_IMS_CLIENT_SECRET'),
    code: pick('ims-code', null, 'IPAAS_IMS_CODE'),
  };
  creds.env = resolve('ipaas-env', 'ipaasEnv', 'IPAAS_ENV', 'prod');
  if (!IPAAS_HOSTS[creds.env]) cli.die(`--ipaas-env must be one of prod, stage, dev (got ${creds.env})`, { prefix: 'change' });
  creds.missing = missing;
  return creds;
}

function makeIpaas(io, creds, opts) {
  const host = IPAAS_HOSTS[creds.env];
  const log = (opts && opts.log) || (() => {});
  let token = null;

  function requireCreds() {
    if (creds.missing.length) {
      cli.die(`--via=ipaas needs credentials that are not configured: ${creds.missing.join(', ')}.\n`
        + `Set them as environment variables, or in the skill config (${skill.dir}/.config) as\n`
        + `ipaasApiKey / imsClientId (secrets belong in the environment, not in the config file).\n`
        + `See ${skill.refs}/ipaas.md. --via=servicenow needs no secrets at all.`, { prefix: 'change' });
    }
  }

  async function getToken() {
    if (token) return token;
    requireCreds();
    const body = new URLSearchParams({
      client_id: creds.clientId, client_secret: creds.clientSecret,
      grant_type: 'authorization_code', code: creds.code,
    });
    const res = await io.fetch(`https://${IMS_HOSTS[creds.env]}/ims/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString(),
    });
    const text = await res.text();
    let j = {};
    try { j = JSON.parse(text); } catch (e) { /* non-JSON */ }
    if (!res.ok || !j.access_token) {
      // Never echo the body: it can contain the secret that was sent back.
      cli.die(`IMS token request failed: HTTP ${res.status}${j.error ? ' — ' + j.error : ''}`
        + `${j.error_description ? ' (' + j.error_description + ')' : ''}`, { prefix: 'change' });
    }
    token = j.access_token;
    return token;
  }

  async function call(method, path, body) {
    const t = await getToken();
    log(`${method} https://${host}${path}`);
    const headers = { Authorization: opts && opts.bearer ? `Bearer ${t}` : t, api_key: creds.apiKey, Accept: 'application/json' };
    const init = { method, headers };
    if (body !== undefined) { init.body = JSON.stringify(body); headers['Content-Type'] = 'application/json'; }
    const res = await io.fetch(`https://${host}${path}`, init);
    const text = await res.text();
    let j = null;
    try { j = JSON.parse(text); } catch (e) { j = { raw: text.slice(0, 300) }; }
    return { status: res.status, ok: res.status >= 200 && res.status < 300, body: j };
  }

  return { requireCreds, call, host };
}

// ─── Repairing an existing change ──────────────────────────────────────────────
//
// A record blanked by an older client (or by any form save that posted an empty widget) cannot
// be closed: the form demands a field the record no longer has, and reconcileForm() fills the
// FORM from the RECORD, so there is nothing to hydrate from. CHG005368783 was stuck in Review
// for exactly this reason and had to be repaired with a Table API PATCH by hand.
//
// Repair is deliberately explicit: rewriting fields on someone else's production change request
// is precisely what this tool exists to prevent, so it never happens implicitly.

/** field → the value this invocation would use, from flags, then config, then defaults. */
function repairValues(o) {
  return {
    u_service_offering_instance: o.instance,
    u_change_approver: o.approver,
    u_hosting_location: HOSTING_LOCATIONS[o.hostingLocation] || o.hostingLocation,
    u_environment: o.environment,
    u_tenant_type: o.tenantType,
    cmdb_ci: o.ci,
    u_change_fixing_cso: o.cso,
  };
}

function forcedFields(flags) {
  const raw = flags['force-field'];
  if (!raw || raw === true) return [];
  return String(raw).split(',').map((x) => x.trim()).filter(Boolean);
}

/**
 * Fill empty tracked fields on an existing change.
 *
 * Only ever fills what is empty; a populated field is left alone unless it is named in
 * --force-field. Nothing outside TRACKED_NAMES can be touched at all.
 */
async function repairChange(sn, sysId, o, opts) {
  const only = (opts && opts.only) || null;      // limit to these fields (used by --repair)
  const forced = (opts && opts.forced) || [];
  const confirm = !!(opts && opts.confirm);
  const log = (opts && opts.log) || console.log;

  const bad = forced.filter((f) => !TRACKED_NAMES.includes(f) && f !== 'work_start' && f !== 'work_end');
  if (bad.length) {
    cli.die(`--force-field: ${bad.join(', ')} ${bad.length > 1 ? 'are' : 'is'} not a repairable field.\n`
      + `Repairable: ${TRACKED_NAMES.join(', ')}`, { prefix: 'change' });
  }

  const wanted = repairValues(o);
  // Actuals are not configuration: only in scope when the caller supplied both explicitly.
  const repairable = TRACKED_NAMES.slice();
  if (o.workStart && o.workEnd) {
    wanted.work_start = snDate(o.workStart);
    wanted.work_end = snDate(o.workEnd);
    repairable.push('work_start', 'work_end');
  }
  const names = (only || repairable).filter((n) => repairable.includes(n));
  const current = await readChange(sn, sysId, names);

  const plan = [];
  for (const field of names) {
    const have = REF(current[field]);
    const want = wanted[field];
    if (!want) { plan.push({ field, have, want, action: 'no value configured' }); continue; }
    if (!have) { plan.push({ field, have, want, action: 'fill' }); continue; }
    if (have === want) { plan.push({ field, have, want, action: 'already correct' }); continue; }
    if (forced.includes(field)) { plan.push({ field, have, want, action: 'overwrite (--force-field)' }); continue; }
    plan.push({ field, have, want, action: 'left alone (populated)' });
  }

  const writes = plan.filter((p) => p.action === 'fill' || p.action.startsWith('overwrite'));
  const body = {};
  for (const p of writes) body[p.field] = p.want;

  const untouched = repairable.filter((n) => !names.includes(n));
  if (untouched.length) log(c.dim(`  not in scope for this repair: ${untouched.join(', ')}`));
  const skipped = plan.filter((p) => p.action !== 'fill' && !p.action.startsWith('overwrite'));
  if (skipped.length) {
    log(c.dim('  leaving alone: ' + skipped.map((p) => `${p.field} (${p.action})`).join(', ')));
  }
  if (skipped.some((p) => p.action === 'left alone (populated)')) {
    log(c.dim('  a populated field is never overwritten — use --force-field=<name> for that field'));
  }
  log(c.dim(`  fields outside the tracked list are never touched: ${repairable.length} repairable here`
    + (o.workStart && o.workEnd ? ' (work_start/work_end included because both were supplied)' : '')));

  if (!writes.length) {
    log('Nothing to repair: every field in scope is populated or has no configured value.');
    return { plan, body, changed: [], verified: true };
  }

  if (!confirm) {
    console.log('DRY RUN — would PATCH /api/now/table/change_request/' + sysId);
    console.log(JSON.stringify(body, null, 2));
    for (const p of writes) console.log(`  ${p.field}: ${JSON.stringify(p.have)} → ${JSON.stringify(p.want)}  [${p.action}]`);
    return { plan, body, changed: [], dryRun: true };
  }

  const res = await sn.api('PATCH', `/api/now/table/change_request/${sysId}`, body,
    { sysparm_display_value: 'false', sysparm_fields: Object.keys(body).join(',') });
  if (!res.ok) throw apiError('repairing the change', res);

  const after = await readChange(sn, sysId, Object.keys(body));
  const rows = writes.map((p) => ({
    field: p.field, before: p.have || '(empty)', after: REF(after[p.field]) || '(empty)',
    ok: REF(after[p.field]) === p.want,
  }));
  const failed = rows.filter((r) => !r.ok);
  log(fmt.table([['field', 'before', 'after']].concat(rows.map((r) => [r.field, r.before, r.after + (r.ok ? '' : '  ✗')]))));
  if (failed.length) {
    throw new Error(`the server did not store ${failed.map((r) => r.field).join(', ')} as sent`
      + ' — read the record and check field-level permissions.');
  }
  return { plan, body, changed: rows, verified: true };
}

// ─── Paper trail ───────────────────────────────────────────────────────────────

function shellQuote(a) {
  return /^[A-Za-z0-9_@%+=:,./-]+$/.test(a) ? a : "'" + String(a).replace(/'/g, "'\\''") + "'";
}
function renderCommand(argv) { return argv.map(shellQuote).join(' '); }

/** Keep head and tail, drop the middle, and say so. */
function truncateOutput(text, max) {
  const t = text || '';
  if (t.length <= max) return { text: t, truncated: false };
  const head = Math.floor(max * 0.6);
  const tail = max - head;
  const omitted = t.length - max;
  return {
    truncated: true,
    text: t.slice(0, head)
      + `\n\n[... ${omitted} characters omitted by \`change\` (cap ${max}); see the run log for the full output ...]\n\n`
      + t.slice(t.length - tail),
  };
}

function buildNotes(o) {
  const cap = truncateOutput(o.output, o.notesMax);
  const lines = [
    o.exitCode === 0 ? 'SUCCESS — wrapped command exited 0' : `FAILURE — wrapped command exited ${o.exitCode}`,
    '',
    'Command as executed:',
    '  ' + o.commandLine,
    'Working directory: ' + o.cwd,
    'Exit code: ' + o.exitCode,
    'Started (UTC): ' + snDate(o.start),
    'Ended (UTC): ' + snDate(o.end),
    'Duration: ' + Math.round((o.end.getTime() - o.start.getTime()) / 1000) + 's',
    'Filed by: change (SLICC skill), transport ' + o.via,
    '',
    cap.text ? 'Output (stdout and stderr, interleaved by stream):' : 'Output: (the command produced none)',
  ];
  if (cap.text) lines.push(cap.text);
  return lines.join('\n');
}

// ─── Option resolution ────────────────────────────────────────────────────────

async function resolveOptions(flags, wrappedArgv) {
  const config = await loadConfig();
  const resolve = resolver(flags, config, process.env);
  const commandLine = wrappedArgv && wrappedArgv.length ? renderCommand(wrappedArgv) : undefined;
  const title = str(flags.title);
  const planUrl = str(flags['plan-url']);

  const o = {
    via: resolve('via', 'via', 'CHANGE_VIA', DEFAULTS.via),
    json: flags.json === true,
    confirm: flags.confirm === true,
    keepOpen: flags['keep-open'] === true,
    repair: flags.repair === true,
    forced: forcedFields(flags),
    bearer: flags.bearer === true,
    title,
    description: str(flags.description) || commandLine || undefined,
    ci: resolve('ci', 'ci', 'CHANGE_CI', DEFAULTS.ci),
    instance: resolve('instance', 'instance', 'CHANGE_INSTANCE', DEFAULTS.instance),
    hostingLocation: resolve('hosting-location', 'hostingLocation', 'CHANGE_HOSTING_LOCATION', DEFAULTS.hostingLocation),
    tenantType: resolve('tenant-type', 'tenantType', null, DEFAULTS.tenantType),
    environment: resolve('environment', 'environment', 'CHANGE_ENVIRONMENT', DEFAULTS.environment),
    customerImpact: resolve('customer-impact', 'customerImpact', null, DEFAULTS.customerImpact),
    complexity: resolve('complexity', 'complexity', null, DEFAULTS.complexity),
    reason: resolve('reason', 'reason', null, DEFAULTS.reason),
    backoutType: resolve('backout-type', 'backoutType', null, DEFAULTS.backoutType),
    validation: resolve('validation', 'validation', null, DEFAULTS.validation),
    riskType: resolve('risk-type', 'riskType', null, DEFAULTS.riskType),
    cso: resolve('cso', 'cso', null, DEFAULTS.cso),
    risk: resolve('risk', 'risk', null, DEFAULTS.risk),
    snImpact: resolve('sn-impact', 'snImpact', null, DEFAULTS.snImpact),
    urgency: resolve('urgency', 'urgency', null, DEFAULTS.urgency),
    scope: resolve('scope', 'scope', null, DEFAULTS.scope),
    chgModel: resolve('chg-model', 'chgModel', null, DEFAULTS.chgModel),
    targetType: DEFAULTS.targetType,
    classification: DEFAULTS.classification,
    documentation: resolve('documentation', 'documentation', null, DEFAULTS.documentation),
    type: DEFAULTS.type,
    category: DEFAULTS.category,
    approver: resolve('approver', 'approver', 'CHANGE_APPROVER', DEFAULTS.approver),
    deployer: resolve('deployer', 'deployer', 'CHANGE_DEPLOYER', DEFAULTS.actor),
    requestedBy: resolve('requested-by', 'requestedBy', null, DEFAULTS.actor),
    submitter: resolve('submitter', 'submitter', null, DEFAULTS.actor),
    leadTime: num(resolve('lead-time', 'leadTime', 'CHANGE_LEAD_TIME', DEFAULTS.leadTime), 'lead-time'),
    duration: num(resolve('duration', 'duration', 'CHANGE_DURATION', DEFAULTS.duration), 'duration'),
    notesMax: num(resolve('notes-max', 'notesMax', 'CHANGE_NOTES_MAX', DEFAULTS.notesMax), 'notes-max'),
    stateTimeout: num(resolve('state-timeout', 'stateTimeout', null, DEFAULTS.stateTimeout), 'state-timeout'),
    formTimeout: num(resolve('form-timeout', 'formTimeout', null, DEFAULTS.formTimeout), 'form-timeout'),
    impactMinutes: resolve('impact-minutes', 'impactMinutes', null, DEFAULTS.impactMinutes),
    closeCode: resolve('close-code', null, null, DEFAULTS.closeCode),
    closeNotes: str(flags['close-notes']),
    workStart: str(flags['work-start']) ? parseUtc(flags['work-start'], '--work-start') : undefined,
    workEnd: str(flags['work-end']) ? parseUtc(flags['work-end'], '--work-end') : undefined,
    planUrl,
    implementationPlan: str(flags['implementation-plan'])
      || (planUrl ? `Runbook: ${planUrl}` : undefined)
      || (commandLine ? `Run, via the \`change\` wrapper: ${commandLine}` : undefined),
    backoutPlan: str(flags['backout-plan']) || 'Revert the command above, or restore the state captured before it ran. Roll back within the planned window.',
    testPlan: str(flags['test-plan']) || 'Verify the command exited 0, then confirm the expected effect through the usual monitoring for this service.',
    justification: str(flags.justification) || 'Operational maintenance, executed through the `change` CMR wrapper so the exact command line is on the ticket.',
    riskAnalysis: str(flags['risk-analysis']) || 'Single scoped command, immediately revertible, executed inside a short planned window.',
  };
  NORMALISE_CHOICES = flags['no-normalise-choices'] !== true;
  if (!NORMALISE_CHOICES) {
    cli.warn('--no-normalise-choices: choice values are sent verbatim. A label written into a'
      + ' choice field displays correctly but does not match any raw-value report or business rule.');
  }
  // Normalise choice values to their RAW form before anything else sees them, so a
  // label supplied by a caller can never be written verbatim into the record.
  o.customerImpact = resolveChoice('u_customer_impact', o.customerImpact, '--customer-impact');
  o.complexity = resolveChoice('u_change_complexity', o.complexity, '--complexity');
  o.reason = resolveChoice('u_cr_reason_justification', o.reason, '--reason');
  o.backoutType = resolveChoice('u_backout_plan_type', o.backoutType, '--backout-type');
  o.validation = resolveChoice('u_production_validation_testing_method', o.validation, '--validation');
  o.tenantType = resolveChoice('u_tenant_type', o.tenantType, '--tenant-type');
  o.environment = resolveChoice('u_environment', o.environment, '--environment');
  o.riskType = resolveChoice('u_risk_type', o.riskType, '--risk-type');
  o.targetType = resolveChoice('u_change_target_type', o.targetType, '--target-type');
  o.classification = resolveChoice('u_classification', o.classification, '--classification');
  o.cso = resolveChoice('u_change_fixing_cso',
    CSO_ALIASES[String(o.cso).toLowerCase()] || o.cso, '--cso (none|fix|prevent)');
  o.closeCode = resolveChoice('close_code', o.closeCode, '--close-code');
  o.type = resolveChoice('type', o.type, '--type');
  if (!['servicenow', 'ipaas'].includes(o.via)) cli.die(`--via must be servicenow or ipaas (got ${o.via})`, { prefix: 'change' });
  if (!(o.duration > 0)) cli.die('--duration must be a positive number of seconds', { prefix: 'change' });
  if (!(o.leadTime >= 0)) cli.die('--lead-time must be zero or more seconds', { prefix: 'change' });
  // Hand-supplied actuals: both or neither, and they must describe a real window.
  if (!!o.workStart !== !!o.workEnd) {
    cli.die('--work-start and --work-end must be given together: they are one window, and half a'
      + ' window on a production change is worse than none.', { prefix: 'change' });
  }
  if (o.workStart && o.workEnd) {
    if (o.workEnd.getTime() < o.workStart.getTime()) {
      cli.die(`--work-end (${snDate(o.workEnd)}) is before --work-start (${snDate(o.workStart)}).`,
        { prefix: 'change' });
    }
    cli.warn(`actual window supplied by hand: ${snDate(o.workStart)} → ${snDate(o.workEnd)} (UTC).`
      + ' These are NOT measured by change. That fact is recorded in the change\'s work notes.');
  }
  if (o.leadTime === 0) {
    cli.warn('--lead-time=0 puts the planned start at «now». ServiceNow reclassifies such a change'
      + ' as type=latent, and the latent state machine refuses Assess → Implement.');
  }
  if (!(o.notesMax > 200)) cli.die('--notes-max must be greater than 200', { prefix: 'change' });
  o._config = config;
  return o;
}

// ─── Dry run rendering ────────────────────────────────────────────────────────

function printDryRun(o, argv, start, end, log) {
  const payload = buildCreatePayload(o, start, end);
  log(c.bold('DRY RUN') + ' — nothing was created, nothing was executed. Add --confirm to file the change for real.');
  log('');
  log(c.bold(`1. POST /api/now/table/change_request`) + `  (${SN_HOST}, page context, X-UserToken)`);
  log(JSON.stringify(payload, null, 2));
  log('');
  log(c.dim(`   planned window: now + ${o.leadTime}s (--lead-time) for ${o.duration}s (--duration).`
    + ' The start must be in the future or ServiceNow reclassifies the change as type=latent,'
    + ' whose state machine refuses Assess → Implement.'));
  log('');
  log(c.bold('2. Verify the planned window sent in step 1')
    + '  (up to 3 reads a second apart, then a PATCH /api/now/table/change_request/{sys_id}'
    + ' repair. The change_request_calendar endpoint is deliberately NOT used: it writes'
    + ' work_start/work_end, not the planned window.)');
  log(JSON.stringify({ start_date: snDate(start), end_date: snDate(end) }, null, 2));
  log('');
  log(c.bold('3. PATCH /api/sn_chg_rest/change/{sys_id}')
    + '  (u_risk_type is derived from service + risk and dropped by create. u_environment is'
    + ' re-sent only if the read-back shows create did not keep it.)');
  log(JSON.stringify({ u_risk_type: o.riskType }, null, 2));
  log('');
  log(c.bold('4. Transitions (form, adb_sysverb_update_and_stay, nextstates checked before each hop)'));
  log(`   New (-5) → Assess (-4) → Implement (-1)`);
  log('');
  log(c.bold('5. Command that would run'));
  log('   ' + renderCommand(argv));
  log(`   cwd: ${process.cwd()}`);
  log('');
  log(c.bold('6. Then'));
  log(`   work_notes ← paper trail (command line, cwd, exit code, output tail capped at ${o.notesMax} chars)`);
  log(`   exit 0    → work_start/work_end, Review (0), close_code=${o.closeCode}, u_impact_minutes=${o.impactMinutes}, Closed (3)`);
  log(`   exit != 0 → failure work_notes, then Canceled (4) with g_form.checkMandatory disabled`);
  log(`   change exits with the wrapped command's exit code either way`);
  if (o.via === 'ipaas') {
    log('');
    log(c.yellow('--via=ipaas selected: the calls above are replaced by the iPaaS contract in references/ipaas.md.'));
  }
}

// ─── Commands ─────────────────────────────────────────────────────────────────

/** CHANGE_VERBOSE=1 traces every HTTP call on stderr. */
function vlog(m) { if (process.env.CHANGE_VERBOSE) console.error(c.dim('  ' + m)); }

function makeLog(json) {
  const buffered = [];
  const log = (...a) => { if (json) buffered.push(a.join(' ')); else console.log(...a); };
  log.buffered = buffered;
  return log;
}

/** Cancel a change that was created but must not be used, and say what happened. */
async function cancelAfterFailure(sn, io, created, o, tail) {
  if (!created || !created.number) return;
  try {
    await cancelChange(sn, io, created.sysId, { timeout: o.stateTimeout });
    await sn.closeFormTab();
    console.error(c.yellow(`${created.number} created and cancelled${tail ? ', ' + tail : ''}.`));
  } catch (cancelErr) {
    console.error(c.red(`${created.number} could not be cancelled automatically: ${cancelErr.message}`));
    console.error(c.red(`Cancel it with:  change --confirm cancel ${created.number}`));
  }
}

async function cmdRun(flags, argv) {
  if (!argv.length) cli.die('usage: change [root params] run <command> [args...]', { prefix: 'change' });
  const o = await resolveOptions(flags, argv);
  if (!o.title) cli.die('--title is required (a one-line description of the change)', { prefix: 'change' });
  const io = await loadIo();
  const log = makeLog(false);
  const start = new Date(io.now().getTime() + o.leadTime * 1000);
  const end = new Date(start.getTime() + o.duration * 1000);

  if (!o.confirm) {
    printDryRun(o, argv, start, end, log);
    return 0;
  }
  if (o.via === 'ipaas') return runViaIpaas(o, argv, io, log, flags);

  const sn = makeSn(io, { log: vlog, formTimeout: o.formTimeout });

  // ── gate: everything up to Implement has to succeed before the command runs ──
  let created;
  try {
    created = await createChange(sn, o, log, start, end);
    log(`${c.bold(c.green(created.number))}  created — ${SN_ORIGIN}/change_request.do?sys_id=${created.sysId}`);
    // Before anything else: if the instance reclassified this change, nothing below is valid.
    await assertRequestedType(sn, created.sysId, o, 'straight after create');
    const win = await setWindow(sn, io, created.sysId, start, end, log);
    log(`  planned window ${win.start_date} → ${win.end_date} (UTC, starts in ${o.leadTime}s)`
      + (win.via === 'create' ? '' : c.yellow(`  [repaired over the ${win.via} API]`)));
    // u_environment lands at create — sys_history_line #0 on CHG005368567, CHG005369180 and
    // CHG005370120 all show u_environment ""→"production" — while u_risk_type is derived and
    // dropped. Rather than trusting either observation forever, re-send whatever came back
    // empty: the deciding read is one this code already makes.
    const after = await readChange(sn, created.sysId, ['u_risk_type', 'u_environment']);
    const resend = { u_risk_type: o.riskType };
    if (!REF(after.u_environment)) {
      log(c.yellow('  u_environment did not survive create on this record, re-sending it'));
      resend.u_environment = o.environment;
    }
    const rest = await setChgRest(sn, created.sysId, resend);
    if (!rest.ok) log(c.yellow(`  warning: ${rest.bad.join(', ')} did not store as sent`));
    const toAssess = await transition(sn, io, created.sysId, S_ASSESS, { timeout: o.stateTimeout, log, expectType: o.type, leadTime: o.leadTime, repair: o.repair, resolved: o, forced: o.forced, ident: o.ident });
    log(`  state → ${STATE[toAssess.state] || toAssess.state}`
      + (toAssess.overshot ? c.yellow(' (ServiceNow advanced it automatically)') : ''));
    // The type flip happens at the first hop, and it also clears three create fields, so
    // check here: the alternative is a confusing «Instance(s) not filled in» two steps later.
    await assertRequestedType(sn, created.sysId, o, 'after the Assess hop');
    const toImpl = await transition(sn, io, created.sysId, S_IMPLEMENT, { timeout: o.stateTimeout, log, expectType: o.type, leadTime: o.leadTime, repair: o.repair, resolved: o, forced: o.forced, ident: o.ident });
    log(`  state → ${STATE[toImpl.state] || toImpl.state}`
      + (toImpl.overshot ? c.yellow(' (ServiceNow advanced it automatically)') : ''));
    await assertRequestedType(sn, created.sysId, o, 'after the Implement hop');
  } catch (err) {
    console.error(c.red('change: the CMR gate failed: ') + err.message);
    console.error(c.red('The wrapped command was NOT executed.'));
    // The change exists but nothing was done under it, so it must not be left open for a
    // human to tidy up. Cancel it here; only ask for manual work if that also fails.
    await cancelAfterFailure(sn, io, created, o, 'command not executed');
    throw new ChangeExit(EXIT_GATE, true);
  }

  // ── run the command ──
  log('');
  log(c.bold('$ ' + renderCommand(argv)));
  const t0 = io.now();
  const res = await io.run(argv);
  const t1 = io.now();
  if (res.stdout) process.stdout.write(res.stdout.endsWith('\n') ? res.stdout : res.stdout + '\n');
  if (res.stderr) process.stderr.write(res.stderr.endsWith('\n') ? res.stderr : res.stderr + '\n');
  const exitCode = res.exitCode === undefined || res.exitCode === null ? 1 : res.exitCode;
  log('');
  log(exitCode === 0 ? c.green(`command exited 0`) : c.red(`command exited ${exitCode}`));

  // ── paper trail ──
  const notes = buildNotes({
    exitCode, commandLine: renderCommand(argv), cwd: io.cwd ? io.cwd() : process.cwd(),
    start: t0, end: t1, output: (res.stdout || '') + (res.stderr ? (res.stdout ? '\n' : '') + res.stderr : ''),
    notesMax: o.notesMax, via: o.via,
  });
  try {
    const posted = await postWorkNotes(sn, created.sysId, notes, renderCommand(argv));
    log(posted.verified
      ? `  work_notes posted to ${created.number} and read back`
      : c.yellow(`  work_notes posted to ${created.number}, but ${JSON.stringify(posted.needle.slice(0, 40))}`
        + ' was not found in the journal on a display read — check the record'));
  } catch (err) {
    console.error(c.yellow(`change: could not post work notes to ${created.number}: ${err.message}`));
  }

  // ── close or cancel ──
  try {
    if (exitCode === 0) {
      await setWorkTimes(sn, created.sysId, t0, t1, log);
      await transition(sn, io, created.sysId, S_REVIEW, { timeout: o.stateTimeout, log, expectType: o.type, leadTime: o.leadTime, repair: o.repair, resolved: o, forced: o.forced, ident: o.ident });
      log('  state → Review');
      if (o.keepOpen) {
        log(c.yellow(`  --keep-open: ${created.number} left in Review, close it yourself with \`change close ${created.number} --confirm\``));
      } else {
        await closeChange(sn, io, created.sysId, {
          closeCode: o.closeCode, impactMinutes: o.impactMinutes, timeout: o.stateTimeout,
          closeNotes: o.closeNotes || `Executed via \`change\`: ${renderCommand(argv)} — exit 0.`,
        });
        log(`  state → Closed (${o.closeCode})`);
      }
    } else {
      await cancelChange(sn, io, created.sysId, { timeout: o.stateTimeout });
      log(`  state → Canceled (the wrapped command failed)`);
    }
  } catch (err) {
    console.error(c.yellow(`change: ${created.number} could not be ${exitCode === 0 ? 'closed' : 'cancelled'}: ${err.message}`));
    console.error(c.yellow(`MANUAL CLEANUP OF ${created.number} IS REQUIRED.`));
  }
  await sn.closeFormTab();

  log('');
  log(`${c.bold(c.green(created.number))}  ${exitCode === 0 ? 'closed successful' : 'canceled'} — exiting with the command's code ${exitCode}`);
  return exitCode;
}

/** Headless iPaaS lifecycle. Unproven: no credentials exist yet. */
async function runViaIpaas(o, argv, io, log, flags) {
  const creds = ipaasCreds(resolver(flags, o._config, process.env), process.env);
  const ip = makeIpaas(io, creds, { bearer: o.bearer, log: vlog });
  ip.requireCreds();
  const start = new Date(io.now().getTime() + o.leadTime * 1000);
  const end = new Date(start.getTime() + o.duration * 1000);
  const body = {
    title: o.title, description: o.description, coordinator: o.deployer,
    customerImpact: 'No Impact', approvedBy: [o.approver], testPlan: o.testPlan,
    implementationPlan: o.implementationPlan, backoutPlan: o.backoutPlan,
    serviceId: Number(o.ci), instances: [{ environment: o.environment, hostingLocation: o.hostingLocation }],
    plannedStartDate: Math.floor(start.getTime() / 1000), plannedEndDate: Math.floor(end.getTime() / 1000),
  };
  let res = await ip.call('POST', '/change_management/changes', body);
  if (!res.ok || !(res.body && (res.body.id || res.body.transactionId))) {
    console.error(c.red('change: iPaaS create failed: HTTP ' + res.status));
    console.error(c.red('The wrapped command was NOT executed.'));
    throw new ChangeExit(EXIT_GATE, true);
  }
  const txId = res.body.id || res.body.transactionId;
  let changeId = null;
  const deadline = io.now().getTime() + 180000;
  while (io.now().getTime() < deadline) {
    const t = await ip.call('GET', `/change_management/transactions/${txId}`);
    const r = (t.body && (t.body.result || t.body)) || {};
    const st = String(r.status || '').toLowerCase();
    if (st === 'success') { changeId = r.changeId; break; }
    if (st === 'failed' || st === 'error') {
      console.error(c.red(`change: iPaaS transaction ${txId} failed: ${r.error || '(no error given)'}`));
      console.error(c.red('The wrapped command was NOT executed.'));
      throw new ChangeExit(EXIT_GATE, true);
    }
    await io.sleep(5000);
  }
  if (!changeId) {
    console.error(c.red(`change: iPaaS transaction ${txId} did not reach Success in time. The wrapped command was NOT executed.`));
    throw new ChangeExit(EXIT_GATE, true);
  }
  log(`${c.bold(c.green(changeId))}  created via iPaaS`);
  log(c.bold('$ ' + renderCommand(argv)));
  const t0 = io.now();
  const r = await io.run(argv);
  const t1 = io.now();
  if (r.stdout) process.stdout.write(r.stdout);
  if (r.stderr) process.stderr.write(r.stderr);
  const exitCode = r.exitCode || 0;
  const notes = buildNotes({
    exitCode, commandLine: renderCommand(argv), cwd: io.cwd ? io.cwd() : process.cwd(),
    start: t0, end: t1, output: (r.stdout || '') + (r.stderr || ''), notesMax: o.notesMax, via: 'ipaas',
  });
  await ip.call('POST', '/change_management/changes', {
    id: changeId, notes, actualStartDate: Math.floor(t0.getTime() / 1000), actualEndDate: Math.floor(t1.getTime() / 1000),
  });
  await ip.call('POST', '/change_management/changes', exitCode === 0
    ? { id: changeId, state: 'Closed', closeCode: 'Successful' }
    : { id: changeId, state: 'Cancelled' });
  log(`${changeId} ${exitCode === 0 ? 'closed' : 'cancelled'} — exiting ${exitCode}`);
  return exitCode;
}

async function cmdCreate(flags) {
  const o = await resolveOptions(flags, null);
  if (!o.title) cli.die('--title is required', { prefix: 'change' });
  const io = await loadIo();
  const log = makeLog(false);
  const start = new Date(io.now().getTime() + o.leadTime * 1000);
  const end = new Date(start.getTime() + o.duration * 1000);
  if (!o.confirm) { printDryRun(o, ['<no command: create only>'], start, end, log); return 0; }
  const sn = makeSn(io, { log: vlog, formTimeout: o.formTimeout });
  const created = await createChange(sn, o, log, start, end);
  let win;
  try {
    await assertRequestedType(sn, created.sysId, o, 'straight after create');
    win = await setWindow(sn, io, created.sysId, start, end, log);
    const afterCreate = await readChange(sn, created.sysId, ['u_environment']);
  const resendFields = { u_risk_type: o.riskType };
  if (!REF(afterCreate.u_environment)) resendFields.u_environment = o.environment;
  await setChgRest(sn, created.sysId, resendFields);
  } catch (err) {
    console.error(c.red('change: ') + err.message);
    await cancelAfterFailure(sn, io, created, o, 'nothing was left in flight');
    throw new ChangeExit(EXIT_GATE, true);
  }
  if (o.json) console.log(JSON.stringify({ number: created.number, sys_id: created.sysId, state: created.state, start_date: win.start_date, end_date: win.end_date }, null, 2));
  else {
    log(`${c.bold(c.green(created.number))}  ${SN_ORIGIN}/change_request.do?sys_id=${created.sysId}`);
    log(`  state ${STATE[created.state] || created.state}, window ${win.start_date} → ${win.end_date} (UTC)`);
  }
  return 0;
}

const GET_FIELDS = ['number', 'sys_id', 'state', 'short_description', 'description', 'start_date', 'end_date',
  'work_start', 'work_end', 'cmdb_ci', 'u_service_offering_instance', 'u_hosting_location', 'u_environment',
  'u_customer_impact', 'u_risk_type', 'risk', 'impact', 'urgency', 'scope', 'u_change_approver',
  'u_change_deployer', 'close_code', 'close_notes', 'u_impact_minutes', 'sys_created_on', 'sys_created_by'];

async function cmdGet(rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  if (!positional[0]) cli.die('usage: change get <CHG…|sys_id> [--json]', { prefix: 'change' });
  const io = await loadIo();
  const sn = makeSn(io, { log: vlog });
  const sysId = await resolveSysId(sn, positional[0]);
  const rec = await readChange(sn, sysId, GET_FIELDS);
  if (flags.json) { console.log(JSON.stringify(rec, null, 2)); return 0; }
  const rows = [
    ['Number', REF(rec.number)],
    ['State', `${STATE[REF(rec.state)] || REF(rec.state)} (${REF(rec.state)})`],
    ['Title', REF(rec.short_description)],
    ['Planned', `${REF(rec.start_date)} → ${REF(rec.end_date)}`],
    ['Actual', `${REF(rec.work_start) || '—'} → ${REF(rec.work_end) || '—'}`],
    ['Service (cmdb_ci)', REF(rec.cmdb_ci)],
    ['Instance', REF(rec.u_service_offering_instance)],
    ['Hosting location', REF(rec.u_hosting_location)],
    ['Environment', REF(rec.u_environment)],
    ['Customer impact', REF(rec.u_customer_impact)],
    ['Risk / impact / urgency / scope', [REF(rec.risk), REF(rec.impact), REF(rec.urgency), REF(rec.scope)].join(' / ')],
    ['Approver', REF(rec.u_change_approver) || '—'],
    ['Close code', REF(rec.close_code) || '—'],
    ['Impact minutes', REF(rec.u_impact_minutes) || '—'],
    ['Created', `${REF(rec.sys_created_on)} by ${REF(rec.sys_created_by)}`],
    ['URL', `${SN_ORIGIN}/change_request.do?sys_id=${sysId}`],
  ];
  for (const [k, v] of rows) console.log(c.dim(k.padEnd(32)) + ' ' + v);
  return 0;
}

async function cmdNotes(rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  const text = positional.slice(1).join(' ');
  if (!positional[0] || !text) cli.die('usage: change notes <CHG…|sys_id> "text" --confirm', { prefix: 'change' });
  const io = await loadIo();
  const sn = makeSn(io, { log: vlog });
  const sysId = await resolveSysId(sn, positional[0]);
  if (!flags.confirm) {
    console.log('DRY RUN — would PATCH /api/now/table/change_request/' + sysId);
    console.log(JSON.stringify({ work_notes: text }, null, 2));
    return 0;
  }
  const r = await postWorkNotes(sn, sysId, text);
  console.log(r.verified ? 'work_notes posted and read back.'
    : c.yellow('work_notes posted, but it could not be read back from the journal — check the record.'));
  return 0;
}

async function cmdStates(rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  if (!positional[0]) cli.die('usage: change states <CHG…|sys_id>', { prefix: 'change' });
  const io = await loadIo();
  const sn = makeSn(io, { log: vlog });
  const sysId = await resolveSysId(sn, positional[0]);
  const cur = REF((await readChange(sn, sysId, ['state', 'number'])).state);
  const ns = await nextStates(sn, sysId);
  if (flags.json) { console.log(JSON.stringify(ns, null, 2)); return 0; }
  console.log(`current: ${STATE[cur] || cur} (${cur})`);
  console.log('available: ' + ns.available.map((s) => `${ns.labels[s] || STATE[s] || '?'} (${s})`).join(', '));
  for (const t of ns.transitions) {
    if (!t) continue;
    const mark = t.transition_available ? c.green('yes') : c.red('no ');
    console.log(`  ${mark}  ${t.display_value || `${t.from_state} → ${t.to_state}`}`
      + (t.automatic_transition ? c.dim(' [automatic]') : ''));
    for (const cond of t.conditions || []) {
      if (!cond) continue;
      console.log(`        ${cond.passed ? c.green('pass') : c.red('FAIL')}  ${(cond.condition && cond.condition.name) || '(unnamed)'}`);
    }
  }
  return 0;
}

async function cmdTransition(name, code, rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  if (!positional[0]) cli.die(`usage: change ${name} <CHG…|sys_id> [--repair] --confirm`, { prefix: 'change' });
  const io = await loadIo();
  const o = await resolveOptions(flags, null);
  const sn = makeSn(io, { log: vlog, formTimeout: o.formTimeout });
  const sysId = await resolveSysId(sn, positional[0]);
  const fields = await readChange(sn, sysId, ['u_risk_type']);
  if (!REF(fields.u_risk_type)) {
    cli.warn('u_risk_type is empty on this change. It is derived from service + risk, dropped by'
      + ` the create call, and only sticks through PATCH /api/sn_chg_rest/change/${sysId} — `
      + 'a change whose create aborted before that step will be missing it.');
  }
  const cur = REF((await readChange(sn, sysId, ['state'])).state);
  if (!flags.confirm) {
    console.log(`DRY RUN — would move ${positional[0]} from ${STATE[cur] || cur} to ${STATE[code]} (${code})`);
    if (name === 'close') console.log(JSON.stringify({ close_code: o.closeCode, u_impact_minutes: String(o.impactMinutes), close_notes: o.closeNotes || 'Closed by `change`.' }, null, 2));
    return 0;
  }
  // Exactly ONE hop per subcommand, and no close-out fields: work_start, work_end,
  // close_code and u_impact_minutes belong to `run` and `close` alone.
  let result;
  if (name === 'close') {
    result = await closeChange(sn, io, sysId, { closeCode: o.closeCode, impactMinutes: o.impactMinutes,
      closeNotes: o.closeNotes, timeout: o.stateTimeout, log: (m) => console.error(m),
      expectType: o.type, leadTime: o.leadTime, repair: o.repair, resolved: o, forced: o.forced,
      ident: positional[0] });
  } else if (name === 'cancel') {
    result = await cancelChange(sn, io, sysId, { timeout: o.stateTimeout });
  } else {
    result = await transition(sn, io, sysId, code, { timeout: o.stateTimeout, log: (m) => console.error(m), expectType: o.type, leadTime: o.leadTime, repair: o.repair, resolved: o, forced: o.forced, ident: positional[0] });
  }
  await sn.closeFormTab();
  if (result && result.overshot) {
    console.log(`${positional[0]} → ${STATE[result.state] || result.state}`);
    cli.warn(`asked for ${STATE[code]} (${code}), but ServiceNow's change model advanced it to `
      + `${STATE[result.state] || result.state} (${result.state}) by itself — Assess → Review is an `
      + 'automatic transition once its conditions pass. This command wrote nothing else.');
  } else {
    console.log(`${positional[0]} → ${STATE[code]}`);
  }
  return 0;
}

function withLabel(field, raw) {
  const label = CHOICES[field] && CHOICES[field][raw];
  return label ? `${raw} (${label})` : `${raw} (unknown value — would be written verbatim)`;
}

/**
 * Read-only diagnostic: open or reuse the change form, wait for it to become scriptable and
 * report what was found. Writes nothing, so it is the safe way to check tab handling and to
 * measure how slow this instance's form really is.
 */
async function cmdForm(rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  if (!positional[0]) cli.die('usage: change form <CHG…|sys_id> [--fresh-tab] [--keep-tab] [--form-timeout=30] [--json]', { prefix: 'change' });
  const io = await loadIo();
  const o = await resolveOptions(flags, null);
  const sn = makeSn(io, { log: vlog, formTimeout: o.formTimeout });
  const sysId = await resolveSysId(sn, positional[0]);
  const t0 = io.now().getTime();
  const tab = await sn.formTab(sysId, flags['fresh-tab'] === true);
  const waited = io.now().getTime() - t0;
  const probe = pageJson(await io.pageEval(tab, jsFormState(sysId)));
  const approver = pageJson(await io.pageEval(tab, jsReadGlideList('u_change_approver')));
  const banner = await sn.banner();
  const owned = sn._state.formTabOwned;
  const out = {
    sys_id: sysId, tab: tab.targetId, tab_opened_by_change: owned,
    ready_after_ms: waited, form_timeout_seconds: o.formTimeout,
    g_form: probe.hasForm, gsftSubmit: probe.hasSubmit, readyState: probe.readyState,
    form_sys_id: probe.sysId, form_state: `${STATE[probe.state] || probe.state} (${probe.state})`,
    u_change_approver_on_form: approver.value || '(empty)',
    banner: banner || '(none)',
  };
  if (o.json) console.log(JSON.stringify(out, null, 2));
  else for (const k of Object.keys(out)) console.log(c.dim(k.padEnd(26)) + ' ' + out[k]);
  if (flags['keep-tab'] !== true) await sn.closeFormTab();
  else if (owned) console.log(c.yellow('--keep-tab: leaving tab ' + tab.targetId + ' open'));
  return 0;
}

async function cmdRepair(rootFlags, tail) {
  const { positional } = parseFlags(tail);
  const flags = mergeFlags(rootFlags, tail);
  if (!positional[0]) {
    cli.die('usage: change repair <CHG…|sys_id> [--instance=… --approver=… …] [--force-field=<name>] --confirm\n'
      + `Repairable fields: ${TRACKED_NAMES.join(', ')}`, { prefix: 'change' });
  }
  const io = await loadIo();
  const o = await resolveOptions(flags, null);
  const sn = makeSn(io, { log: vlog, formTimeout: o.formTimeout });
  const sysId = await resolveSysId(sn, positional[0]);
  const state = REF((await readChange(sn, sysId, ['state'])).state);
  console.log(`${positional[0]}  state ${STATE[state] || state} (${state})`
    + '  — repair works in any state, that is the point');
  const r = await repairChange(sn, sysId, o, {
    forced: forcedFields(flags), confirm: flags.confirm === true, log: (m) => console.log(m),
  });
  if (r.dryRun) console.log('\nAdd --confirm to apply.');
  else if (r.changed.length) console.log(`Repaired ${r.changed.length} field${r.changed.length > 1 ? 's' : ''} on ${positional[0]}.`);
  return 0;
}

async function cmdConfig(flags) {
  const o = await resolveOptions(flags, null);
  const cfg = o._config;
  const creds = ipaasCreds(resolver(flags, cfg, process.env), process.env);
  const out = {
    transport: o.via,
    servicenow: {
      host: SN_HOST, form_view: FORM_VIEW,
      cmdb_ci: o.ci, service_offering_instance: o.instance,
      hosting_location: `${o.hostingLocation} (${HOSTING_LOCATIONS[o.hostingLocation] || 'unmapped, sent verbatim'})`,
      // choice fields are shown as «raw (label)» so a label-shaped value is obvious
      environment: withLabel('u_environment', o.environment),
      tenant_type: withLabel('u_tenant_type', o.tenantType),
      customer_impact: withLabel('u_customer_impact', o.customerImpact),
      complexity: withLabel('u_change_complexity', o.complexity),
      reason: withLabel('u_cr_reason_justification', o.reason),
      backout_plan_type: withLabel('u_backout_plan_type', o.backoutType),
      validation_method: withLabel('u_production_validation_testing_method', o.validation),
      risk_type: withLabel('u_risk_type', o.riskType),
      change_fixing_cso: withLabel('u_change_fixing_cso', o.cso),
      target_type: withLabel('u_change_target_type', o.targetType),
      classification: withLabel('u_classification', o.classification),
      change_type: withLabel('type', o.type),
      risk: o.risk, impact: o.snImpact, urgency: o.urgency, scope: o.scope,
      deployer: o.deployer, requested_by: o.requestedBy, submitter: o.submitter, approver: o.approver,
      secrets_required: 'none',
    },
    ipaas: {
      env: creds.env, host: IPAAS_HOSTS[creds.env], ims_host: IMS_HOSTS[creds.env],
      credentials_present: creds.missing.length === 0,
      credentials_missing: creds.missing,
    },
    defaults: { lead_time_seconds: o.leadTime, duration_seconds: o.duration, notes_max_chars: o.notesMax, state_timeout_seconds: o.stateTimeout, form_timeout_seconds: o.formTimeout, close_code: withLabel('close_code', o.closeCode), impact_minutes: o.impactMinutes },
    config_file: `${skill.dir}/.config`,
    config_keys_set: Object.keys(cfg),
  };
  if (o.json) { console.log(JSON.stringify(out, null, 2)); return 0; }
  const print = (obj, indent) => {
    for (const k of Object.keys(obj)) {
      const v = obj[k];
      if (v && typeof v === 'object' && !Array.isArray(v)) { console.log(indent + c.bold(k + ':')); print(v, indent + '  '); }
      else console.log(indent + c.dim(k.padEnd(28)) + ' ' + (Array.isArray(v) ? (v.length ? v.join(', ') : '—') : String(v)));
    }
  };
  print(out, '');
  return 0;
}

function help() {
  const L = [
    c.bold('change') + ' — wrap any command in an Adobe ServiceNow Change Management Request',
    '',
    '  change [root params] run <command> [args...]',
    '',
    'Everything before `run` configures the CMR. Everything after `run` is executed',
    "verbatim, recorded on the ticket, and `change` exits with that command's code.",
    'Without --confirm every path is a dry run: it prints the payload and the command',
    'and calls nothing.',
    '',
    c.bold('Subcommands'),
    '  run <cmd...>             File a CMR, run the command, paper-trail it, close or cancel',
    '  create                   File a CMR and stop (state New)',
    '  get <CHG|sys_id>         Show a change',
    '  notes <CHG> "text"       Append work notes',
    '  assess|implement|review <CHG>   Move one hop through the lifecycle',
    '  close <CHG>              close_code + Closed',
    '  cancel <CHG>             Canceled',
    '  states <CHG>             Pretty-print nextstates with per-transition conditions',
    '  form <CHG>               Read-only: open/reuse the form, time readiness, report what',
    '                           the form holds (record value versus form node)',
    '  repair <CHG>             Fill EMPTY tracked fields on an existing change, e.g. a record',
    '                           an older client blanked. Never overwrites a populated field',
    '                           without --force-field=<name>. Works in any state',
    '  config                   Show resolved non-secret configuration',
    '',
    c.bold('Root params') + ' (--flag=value only)',
    '  --title=…                Required for run/create',
    '  --description=…          Defaults to the wrapped command line',
    '  --plan-url=…             Runbook URL, becomes the implementation plan',
    '  --implementation-plan=…  --backout-plan=…  --test-plan=…  --justification=…  --risk-analysis=…',
    '  --ci=…                   cmdb_ci sys_id (default: EDS Delivery)',
    '  --instance=…             u_service_offering_instance sys_id',
    '  --hosting-location=USA1  --environment=production  --tenant-type=Multi',
    '  --customer-impact=none   --complexity=…  --reason=Maintenance  --backout-type=…  --validation=…',
    '  --risk-type=Minor        --risk=4  --sn-impact=2  --urgency=2  --scope=3',
    '  --cso=none|fix|prevent   Change is related to an emergency (mandatory for New → Assess;',
    '                           none = not an emergency, fix = fixes a CSO, prevent = prevents one)',
    '  --approver=<sys_id>      --deployer=<sys_id>  --requested-by=…  --submitter=…',
    '  --lead-time=300          Seconds from now to the planned START (must be > 0: a start',
    '                           that is not in the future makes ServiceNow reclassify the',
    '                           change as latent, which cannot reach Implement)',
    '  --duration=600           Planned window length, seconds',
    '  --notes-max=4000         Cap on the captured output in work_notes',
    '  --state-timeout=60       Seconds to wait for a state transition to land',
    '  --form-timeout=30        Seconds to wait for the change form to become scriptable',
    '  --impact-minutes=0       --close-code=successful  --close-notes=…',
    '  --work-start=<UTC> --work-end=<UTC>',
    '                           Hand-supplied ACTUAL window for a bare review hop or repair.',
    '                           Both required together; disclosed on stderr and in the work',
    '                           notes. `change run` measures them instead',
    '  --keep-open              Stop at Review instead of closing',
    '  --no-normalise-choices   Send choice values verbatim (see SKILL.md: raw vs label)',
    '  --via=servicenow|ipaas   Transport (default servicenow, needs no secrets)',
    '  --repair                 On a hop or close: fill the fields that hop needs if the record',
    '                           has them empty, then continue (same rules as the repair subcommand)',
    '  --force-field=<name>     Let repair overwrite this populated field (repeatable)',
    '  --confirm                Required for anything that writes',
    '  --json                   Machine-readable output where it makes sense',
    '',
    c.bold('Example'),
    '  change --title="Flatten *.aem.live WRR to plain CNAME" --plan-url=https://example/runbook#dns \\',
    '         --confirm run gcloud dns records add aem-live \'*.aem.live.\' CNAME n.sni.global.fastly.net. --ttl 300',
    '',
    'CHANGE_VERBOSE=1 logs every HTTP call. See references/servicenow.md and references/ipaas.md.',
  ];
  console.log(L.join('\n'));
}

// ─── Main ─────────────────────────────────────────────────────────────────────

const { root, sub, tail } = splitArgv(process.argv.slice(2));

if (!sub || sub === 'help' || root.help === true) { help(); process.exit(sub ? 0 : 1); }
if (!SUBCOMMANDS.includes(sub)) {
  console.error(`change: unknown subcommand ${JSON.stringify(sub)}. Root params must come before it and use --flag=value form.`);
  help();
  process.exit(1);
}

let code = 0;
try {
  switch (sub) {
    case 'run': code = await cmdRun(root, tail); break;
    case 'create': code = await cmdCreate(root); break;
    case 'get': code = await cmdGet(root, tail); break;
    case 'notes': code = await cmdNotes(root, tail); break;
    case 'assess': code = await cmdTransition('assess', S_ASSESS, root, tail); break;
    case 'implement': code = await cmdTransition('implement', S_IMPLEMENT, root, tail); break;
    case 'review': code = await cmdTransition('review', S_REVIEW, root, tail); break;
    case 'close': code = await cmdTransition('close', S_CLOSED, root, tail); break;
    case 'cancel': code = await cmdTransition('cancel', S_CANCELED, root, tail); break;
    case 'states': code = await cmdStates(root, tail); break;
    case 'form': code = await cmdForm(root, tail); break;
    case 'repair': code = await cmdRepair(root, tail); break;
    case 'config': code = await cmdConfig(root); break;
    default: help(); code = 1;
  }
} catch (err) {
  // Resolve, never exit here: process.exit() inside a catch inside an async function is
  // exactly the shape that produced an exit code of 0 on a failed gate. There is one
  // process.exit() in this file, below, and it is the last statement.
  if (err instanceof ChangeExit) {
    if (!err.silent) console.error(c.red('change: ') + err.message);
    code = err.exitCode;
  } else if (err && (err.name === 'NodeExitError' || typeof err.code === 'number')) {
    // cli.die() and any stray process.exit() land here.
    code = typeof err.code === 'number' ? err.code : EXIT_USAGE;
  } else {
    console.error(c.red('change: ') + ((err && err.message) || String(err)));
    code = EXIT_USAGE;
  }
}
if (typeof code !== 'number' || !Number.isFinite(code)) code = EXIT_USAGE;
process.exit(code);
