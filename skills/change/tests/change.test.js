// Behaviour tests for scripts/change.jsh. Run from skills/change/:
//
//   tst tests/change.test.js
//
// The script runs inside an AsyncFunction with mocked require/process/console. The
// ServiceNow transport calls browser.fetch(tab, url, init) in a logged-in tab, so that is the
// fake fetch here: an in-memory change record plus its work-notes journal. Nothing reaches a
// real system. The fake stops every run at the first form interaction (browser.eval of
// anything but window.g_ck, or opening a form tab, throws STOP_AT_FORM), which is AFTER the pre-hop checks and writes
// these tests are about and BEFORE any state submit.

import test, { is, ok } from 'tst';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(__dirname, '../scripts/change.jsh');
const source = fs.readFileSync(target, 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

class ExitError extends Error {
  constructor(code) {
    super('exit ' + code);
    this.exitCode = code;
  }
}

const SYS_ID = '0123456789abcdef0123456789abcdef';
const NUMBER = 'CHG000000001';
const WS = '2026-08-18 09:00:00';
const WE = '2026-08-18 09:12:00';
const STOP = 'STOP_AT_FORM';

function baseRecord(extra) {
  return Object.assign({
    sys_id: SYS_ID, number: NUMBER, state: '-1', type: 'standard', u_risk_type: 'Minor',
    u_service_offering_instance: 'inst', u_change_approver: 'appr', u_hosting_location: 'loc',
    u_environment: 'production', u_tenant_type: 'Multi', cmdb_ci: 'ci',
    u_change_fixing_cso: 'No - non-emergency', work_start: '', work_end: '',
  }, extra || {});
}

/**
 * Fake page-context fetch against one change record. `failNotes` makes the work-notes PATCH
 * return 500; `dropNotes` accepts it but never shows it in the journal.
 */
function fakeServiceNow(requests, record, opts) {
  const o = opts || {};
  const state = { journal: o.journal || '' };
  return async (tab, url, init) => {
    const u = new URL(url, 'https://adobe.service-now.com');
    const method = (init && init.method) || 'GET';
    const body = init && init.body ? JSON.parse(init.body) : undefined;
    requests.push({ method, path: u.pathname, body, display: u.searchParams.get('sysparm_display_value') });
    const q = u.searchParams.get('sysparm_query') || '';
    const fields = (u.searchParams.get('sysparm_fields') || '').split(',').filter(Boolean);
    const pick = () => {
      const out = {};
      for (const f of (fields.length ? fields : Object.keys(record))) out[f] = record[f] === undefined ? '' : record[f];
      return out;
    };
    if (u.pathname.endsWith('/nextstates')) {
      return { status: 200, body: { result: { available_states: ['0', '4'], state_label: {}, state_transitions: [] } } };
    }
    if (u.pathname === '/api/now/table/change_request' && method === 'GET') {
      if (q.startsWith('number=')) return { status: 200, body: { result: [{ sys_id: SYS_ID, number: NUMBER }] } };
      if (q.startsWith('sys_id=')) return { status: 200, body: { result: [{ work_notes: state.journal }] } };
    }
    if (u.pathname === `/api/now/table/change_request/${SYS_ID}`) {
      if (method === 'GET') return { status: 200, body: { result: pick() } };
      if (method === 'PATCH') {
        if (body.work_notes !== undefined) {
          if (o.failNotes) return { status: 500, body: { error: { message: 'simulated note failure' } } };
          if (!o.dropNotes) state.journal = `08-18-2026 09:30:00 - Test User (Work notes)\n${body.work_notes}\n\n${state.journal}`;
          return { status: 200, body: { result: { sys_id: SYS_ID } } };
        }
        Object.assign(record, body);
        return { status: 200, body: { result: pick() } };
      }
    }
    return { status: 404, body: { error: { message: 'fake: unrouted ' + method + ' ' + u.pathname } } };
  };
}

async function runChange(args, { record = baseRecord(), sn = {} } = {}) {
  const requests = [];
  const stdout = [];
  const stderr = [];
  const identity = (s) => String(s);
  const color = { bold: identity, dim: identity, green: identity, red: identity, yellow: identity };
  const exec = async () => ({ stdout: '[ABCDEF0123] https://adobe.service-now.com/now/nav/ui/home\n', stderr: '', exitCode: 0 });
  exec.spawn = async () => { throw new Error(STOP + ' (playwright-cli open)'); };
  const mocks = {
    'sliccy:exec': exec,
    'sliccy:browser': {
      eval: async (tab, expr) => {
        if (expr === 'window.g_ck') return 'test-session-token-000';
        throw new Error(STOP);
      },
      fetch: fakeServiceNow(requests, record, sn),
    },
    'sliccy:skill': { config: async () => ({}), dir: '/skills/change', refs: '/skills/change/references' },
    'sliccy:cli': {
      die: (msg) => { stderr.push(String(msg)); const e = new Error(String(msg)); e.code = 1; throw e; },
      warn: (msg) => stderr.push(String(msg)),
    },
    'sliccy:color': color,
    'sliccy:fmt': { table: (rows) => rows.map((r) => r.join(' | ')).join('\n') },
    fs: { readFile: async () => { throw new Error('ENOENT'); } },
  };
  const mockRequire = (id) => {
    if (Object.hasOwn(mocks, id)) return mocks[id];
    throw new Error('module not available in test: ' + id);
  };
  const mockProcess = {
    argv: ['node', target, ...args],
    env: {},
    cwd: () => '/tmp',
    stdout: { write: (m) => stdout.push(String(m)) },
    stderr: { write: (m) => stderr.push(String(m)) },
    exit: (code) => { throw new ExitError(code); },
  };
  const mockConsole = {
    log: (m) => stdout.push(String(m)),
    error: (m) => stderr.push(String(m)),
    warn: (m) => stderr.push(String(m)),
  };
  let error = null;
  let exitCode = null;
  try {
    await new AsyncFunction('require', 'process', 'console', 'fetch', source)(
      mockRequire, mockProcess, mockConsole, async () => { throw new Error('global fetch must not be used'); },
    );
  } catch (e) {
    if (e instanceof ExitError) exitCode = e.exitCode; else error = e;
  }
  const writes = requests.filter((r) => r.method !== 'GET');
  return { error, exitCode, requests, writes, stdout, stderr, record, err: stderr.join('\n') };
}

const HAND = [`--work-start=${WS}`, `--work-end=${WE}`, '--confirm', 'review', NUMBER];
const isNote = (r) => r.method === 'PATCH' && r.body && r.body.work_notes !== undefined;
const isActuals = (r) => r.method === 'PATCH' && r.body && (r.body.work_start !== undefined || r.body.work_end !== undefined);

test('review with hand-supplied actuals posts the disclosure note BEFORE writing the actuals', async () => {
  const r = await runChange(HAND);
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  const note = r.requests.findIndex(isNote);
  const actuals = r.requests.findIndex(isActuals);
  ok(note >= 0, 'no work-notes PATCH was sent');
  ok(actuals >= 0, 'no work_start/work_end PATCH was sent');
  ok(note < actuals, `note PATCH is request #${note}, actuals PATCH is #${actuals}`);
  ok(r.requests[note].body.work_notes.includes(`work_start: ${WS} UTC`), 'note does not name work_start');
  ok(r.requests[note].body.work_notes.includes(`work_end: ${WE} UTC`), 'note does not name work_end');
  is(r.requests[actuals].body.work_start, WS);
  is(r.requests[actuals].body.work_end, WE);
  ok(r.err.includes(STOP), 'the hop did not reach the form: ' + r.err.slice(-300));
});

test('a failing disclosure note leaves the actuals unwritten and refuses the hop', async () => {
  const r = await runChange(HAND, { sn: { failNotes: true } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.exitCode, 1);
  is(r.requests.filter(isActuals).length, 0, 'actuals were PATCHed despite the failed note');
  is(r.record.work_start, '');
  is(r.record.work_end, '');
  ok(r.err.includes('NOT'), 'no refusal message: ' + r.err.slice(-300));
  ok(!r.err.includes(STOP), 'the hop went on to the form');
});

test('a disclosure note that cannot be read back also leaves the actuals unwritten', async () => {
  const r = await runChange(HAND, { sn: { dropNotes: true } });
  is(r.exitCode, 1);
  is(r.requests.filter(isActuals).length, 0, 'actuals were PATCHed despite an unverified note');
  ok(!r.err.includes(STOP), 'the hop went on to the form');
});

test('retry: actuals already on the record with no disclosing note are refused, nothing written', async () => {
  const r = await runChange(HAND, { record: baseRecord({ work_start: WS, work_end: WE }) });
  is(r.exitCode, 1);
  is(r.writes.length, 0, 'wrote: ' + JSON.stringify(r.writes));
  ok(r.err.includes('no work note'), 'no refusal naming the missing note: ' + r.err.slice(-300));
  ok(r.err.includes(`change notes ${NUMBER}`), 'refusal does not give the disclosing command');
  ok(!r.err.includes(STOP), 'the hop went on to the form');
});

test('retry: actuals already on the record AND disclosed in the journal proceed without writing', async () => {
  const journal = `08-18-2026 09:30:00 - Test User (Work notes)\nActual execution window supplied by hand, not measured by the change wrapper.\nwork_start: ${WS} UTC\nwork_end: ${WE} UTC\n`;
  const r = await runChange(HAND, { record: baseRecord({ work_start: WS, work_end: WE }), sn: { journal } });
  is(r.writes.length, 0, 'wrote: ' + JSON.stringify(r.writes));
  ok(r.err.includes(STOP), 'the hop did not reach the form: ' + r.err.slice(-300));
});

test('retry: different actuals on the record are refused, never overwritten', async () => {
  const r = await runChange(HAND, { record: baseRecord({ work_start: '2026-08-18 08:00:00', work_end: WE }) });
  is(r.exitCode, 1);
  is(r.writes.length, 0, 'wrote: ' + JSON.stringify(r.writes));
  ok(!r.err.includes(STOP), 'the hop went on to the form');
});

test('repair with --work-start/--work-end discloses before the PATCH, and a failed note stops it', async () => {
  const args = ['repair', NUMBER, `--work-start=${WS}`, `--work-end=${WE}`, '--confirm'];
  const good = await runChange(args);
  is(good.error, null, 'threw: ' + (good.error && good.error.message));
  const note = good.requests.findIndex(isNote);
  const actuals = good.requests.findIndex(isActuals);
  ok(note >= 0 && actuals >= 0, 'expected both a note and an actuals PATCH');
  ok(note < actuals, `note PATCH is request #${note}, actuals PATCH is #${actuals}`);
  const bad = await runChange(args, { sn: { failNotes: true } });
  is(bad.exitCode, 1);
  is(bad.requests.filter(isActuals).length, 0, 'repair PATCHed actuals despite the failed note');
});

test('change --help, -h and help exit 0; a bare change and an unknown subcommand exit 1', async () => {
  for (const args of [['--help'], ['-h'], ['help']]) {
    const r = await runChange(args);
    is(r.exitCode, 0, args.join(' '));
    ok(r.stdout.join('\n').includes('Subcommands'), args.join(' ') + ': no help text');
    is(r.requests.length, 0, args.join(' ') + ': made requests');
  }
  is((await runChange([])).exitCode, 1, 'bare change');
  is((await runChange(['bogus'])).exitCode, 1, 'unknown subcommand');
});
