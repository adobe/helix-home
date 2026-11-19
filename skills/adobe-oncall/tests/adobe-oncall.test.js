// Behaviour tests for scripts/oncall.jsh. Run from skills/adobe-oncall/:
//
//   tst tests/adobe-oncall.test.js
//
// The script runs inside an AsyncFunction with mocked require/process/console.
// It has no fetch: every ServiceNow request is an XHR written to a temp file
// with fs.writeFile and executed in the browser tab via
// `exec('playwright-cli eval-file <file> --tab=<id>')`. The fake exec below is
// that network boundary: it reads the generated XHR code, records the method
// and path passed to xhr.open(), and answers with canned Table API JSON.
// No request leaves the test.

import test, { is, ok } from 'tst';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(__dirname, '../scripts/oncall.jsh');
const source = fs.readFileSync(target, 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

class ExitError extends Error {
  constructor(code) {
    super('exit ' + code);
    this.exitCode = code;
  }
}

const TAB_LIST = '[ABCDEF0123] https://adobe.service-now.com/x/adosy/on-call/home\n';

async function runOncall(args, { rows = [] } = {}) {
  const requests = [];
  const stdout = [];
  const stderr = [];
  const files = {};
  const fakeFs = {
    writeFile: async (p, content) => { files[p] = String(content); },
    rm: async (p) => { delete files[p]; },
    readFileSync: () => { throw new Error('ENOENT'); },
    writeFileSync: () => { throw new Error('unexpected local write in test'); },
  };
  const fakeExec = async (cmd) => {
    if (cmd === 'playwright-cli tab-list') return { exitCode: 0, stdout: TAB_LIST, stderr: '' };
    const m = /^playwright-cli eval-file (\S+) --tab=/.exec(cmd);
    if (!m) throw new Error('unexpected exec in test: ' + cmd);
    const code = files[m[1]] || '';
    const open = /xhr\.open\("([A-Z]+)", ("(?:[^"\\]|\\.)*")\)/.exec(code);
    if (!open) throw new Error('no xhr.open in eval code');
    const req = { method: open[1], path: JSON.parse(open[2]) };
    requests.push(req);
    if (req.method !== 'GET') throw new Error('unexpected write request in test: ' + req.method);
    return { exitCode: 0, stdout: JSON.stringify({ result: rows }), stderr: '' };
  };
  const mocks = { 'sliccy:exec': fakeExec, fs: fakeFs };
  const mockRequire = (id) => {
    if (Object.hasOwn(mocks, id)) return mocks[id];
    throw new Error('module not available in test: ' + id);
  };
  const mockProcess = {
    argv: ['node', target, ...args],
    env: {},
    exit: (code) => { throw new ExitError(code); },
  };
  const mockConsole = {
    log: (msg) => stdout.push(String(msg)),
    error: (msg) => stderr.push(String(msg)),
    warn: (msg) => stderr.push(String(msg)),
  };
  let error = null;
  try {
    await new AsyncFunction('require', 'process', 'console', source)(mockRequire, mockProcess, mockConsole);
  } catch (e) {
    error = e;
  }
  const query = requests.length
    ? new URL('https://x' + requests[0].path).searchParams.get('sysparm_query')
    : null;
  return { error, requests, stdout, stderr, query };
}

const clauses = (q) => q.split('^');

test('incidents default: stateIN1,2,60 with active=true', async () => {
  const r = await runOncall(['incidents']);
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.requests.length, 1);
  ok(clauses(r.query).includes('active=true'), 'query: ' + r.query);
  ok(clauses(r.query).includes('stateIN1,2,60'), 'query: ' + r.query);
  is(r.stdout.join('\n'), 'No active on-call incidents.');
});

test('incidents --state=resolved: state 6 and no active=true', async () => {
  const r = await runOncall(['incidents', '--state=resolved', '--group=f3483b5047f11610c49b3d54116d4348']);
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.requests.length, 1);
  ok(clauses(r.query).includes('stateIN6'), 'query: ' + r.query);
  ok(!clauses(r.query).includes('active=true'), 'active=true still sent: ' + r.query);
  ok(clauses(r.query).includes('assignment_group=f3483b5047f11610c49b3d54116d4348'), 'query: ' + r.query);
});

test('incidents --state=cancelled and --state=all omit active=true and keep the 20-row limit', async () => {
  for (const [state, expect] of [['cancelled', 'stateIN8'], ['all', 'stateIN1,-5,2,6,8,60'], ['6', 'stateIN6']]) {
    const r = await runOncall(['incidents', '--state=' + state]);
    is(r.error, null, state + ' threw: ' + (r.error && r.error.message));
    ok(clauses(r.query).includes(expect), state + ' query: ' + r.query);
    ok(!clauses(r.query).includes('active=true'), state + ' still sends active=true: ' + r.query);
    is(new URL('https://x' + r.requests[0].path).searchParams.get('sysparm_limit'), '20', state);
  }
});

test('incidents --state=pending (open-type) keeps active=true', async () => {
  const r = await runOncall(['incidents', '--state=pending']);
  ok(clauses(r.query).includes('stateIN-5'), 'query: ' + r.query);
  ok(clauses(r.query).includes('active=true'), 'query: ' + r.query);
});

test('empty result for a closed state does not say "No active"', async () => {
  const r = await runOncall(['incidents', '--state=resolved']);
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  const out = r.stdout.join('\n');
  ok(!/No active/.test(out), 'message: ' + out);
  is(out, 'No on-call incidents in state 6.');
});

test('incidents --state=resolved prints the returned rows', async () => {
  const rows = [{
    number: 'OCINC0000001', short_description: ' test ', state: { display_value: 'Resolved' },
    priority: { display_value: '3 - Moderate' }, assigned_to: { display_value: 'A' },
    assignment_group: { display_value: 'AEM - Helix v2' }, opened_at: '2026-09-01 00:00:00', sys_id: 'abc',
  }];
  const r = await runOncall(['incidents', '--state=resolved'], { rows });
  const out = JSON.parse(r.stdout.join('\n'));
  is(out.length, 1);
  is(out[0].number, 'OCINC0000001');
  is(out[0].state, 'Resolved');
});

test('SKILL.md documents history and the whoisoncall alias', async () => {
  const skill = fs.readFileSync(path.resolve(__dirname, '../SKILL.md'), 'utf8');
  ok(/^### oncall history/m.test(skill), 'history section missing');
  ok(/whoisoncall/.test(skill), 'whoisoncall alias missing');
  ok(source.includes("case 'history'"), 'history command missing from script');
  ok(source.includes("case 'whoisoncall'"), 'whoisoncall command missing from script');
});

test('no orphaned WATCH_INSTRUCTION comment above cmdWatch', async () => {
  ok(!source.includes('The standing instruction handed to the investigator scoop'), 'orphaned comment still present');
});
