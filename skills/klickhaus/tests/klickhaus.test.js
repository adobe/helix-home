// Behaviour tests for scripts/klickhaus.jsh. Run from skills/klickhaus/:
//
//   tst tests/klickhaus.test.js
//
// The script runs inside an AsyncFunction with mocked require/process/console
// and a fake ClickHouse HTTP endpoint. The fake models the one ClickHouse
// behaviour these tests depend on: the output format is the SQL's own
// `FORMAT x` clause (after `--` line comments are stripped), else the
// `default_format` URL parameter, else TabSeparated.

import test, { is, ok } from 'tst';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(__dirname, '../scripts/klickhaus.jsh');
const source = fs.readFileSync(target, 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

class ExitError extends Error {
  constructor(code) {
    super('exit ' + code);
    this.exitCode = code;
  }
}

// Opaque subsystem ids as they appear in the backend table: the first token
// of a TabSeparated body is not valid JSON.
const BACKEND_ROWS = [
  { subsystem: 'SIDuP3HxleUgBDR3Gi8T24', c: 66600 },
  { subsystem: 'cHpjIl1WNRu9SFyL1eBSj3', c: 65800 },
];

function fakeClickHouse(requests, rows) {
  return async (url, init) => {
    const sql = String(init.body);
    requests.push({ url: String(url), sql });
    const code = sql.replace(/--[^\n]*/g, '');
    const formats = code.match(/\bFORMAT\s+\w+/gi) || [];
    if (formats.length > 1) {
      return new Response('Code: 62. DB::Exception: Syntax error (FORMAT)', { status: 400 });
    }
    const format = formats.length
      ? formats[0].split(/\s+/)[1]
      : (new URL(String(url)).searchParams.get('default_format') || 'TabSeparated');
    const data = /helix\.ref/.test(sql) ? [] : rows;
    if (format === 'JSON') {
      return new Response(JSON.stringify({ meta: [], data, rows: data.length }), { status: 200 });
    }
    return new Response(data.map((r) => Object.values(r).join('\t') + '\n').join(''), { status: 200 });
  };
}

async function runKlickhaus(args, { rows = BACKEND_ROWS, files = {}, stdin = null } = {}) {
  const requests = [];
  const stdout = [];
  const stderr = [];
  const mocks = {
    'sliccy:skill': { config: async () => ({ user: 'test-user', password: 'test-secret' }) },
    fs: {
      readFileSync: (p) => {
        if (!Object.hasOwn(files, p)) throw new Error('ENOENT ' + p);
        return files[p];
      },
    },
  };
  const mockRequire = (id) => {
    if (Object.hasOwn(mocks, id)) return mocks[id];
    throw new Error('module not available in test: ' + id);
  };
  const mockStdin = {
    isTTY: stdin === null,
    setEncoding: () => {},
    on: (event, fn) => {
      if (event === 'data' && stdin) setTimeout(() => fn(stdin), 0);
      if (event === 'end') setTimeout(fn, 1);
    },
  };
  const mockProcess = {
    argv: ['node', target, ...args],
    env: {},
    stdin: mockStdin,
    exit: (code) => { throw new ExitError(code); },
  };
  const mockConsole = {
    log: (m) => stdout.push(String(m)),
    error: (m) => stderr.push(String(m)),
    warn: (m) => stderr.push(String(m)),
  };
  let error = null;
  try {
    await new AsyncFunction('require', 'process', 'console', 'fetch', source)(
      mockRequire, mockProcess, mockConsole, fakeClickHouse(requests, rows),
    );
  } catch (e) {
    error = e;
  }
  let output = null;
  try { output = JSON.parse(stdout.join('\n')); } catch (_) { /* leave null */ }
  return { error, requests, stdout, stderr, output };
}

const BACKEND_SQL = 'SELECT subsystem, sum(weight) c FROM backend WHERE timestamp >= now() - INTERVAL 10 MINUTE GROUP BY subsystem ORDER BY c DESC LIMIT 10';

test('query --table=backend returns rows whose first value is an opaque id', async () => {
  const r = await runKlickhaus(['query', BACKEND_SQL, '--table=backend']);
  is(r.error, null, 'query threw: ' + (r.error && r.error.message));
  ok(r.output, 'stdout is not JSON: ' + r.stdout.join('\n').slice(0, 80));
  is(r.output.data.length, 2);
  is(r.output.data[0].subsystem, 'SIDuP3HxleUgBDR3Gi8T24');
  is(r.output.data[0].c, 66600);
});

test('query flags are not sent to ClickHouse as SQL', async () => {
  const r = await runKlickhaus(['query', BACKEND_SQL, '--table=backend', '--range=1h']);
  is(r.requests.length, 1);
  ok(!r.requests[0].sql.includes('--table'), 'flag leaked into SQL: ' + r.requests[0].sql);
  ok(!r.requests[0].sql.includes('--range'), 'flag leaked into SQL: ' + r.requests[0].sql);
});

test('query with an empty result on a non-delivery table returns an empty data array', async () => {
  const sql = "SELECT `helix.ref` ref, subsystem, sum(weight) c FROM backend WHERE timestamp >= now() - INTERVAL 1 HOUR AND `helix.repo` = 'nope' GROUP BY ref, subsystem";
  const r = await runKlickhaus(['query', sql, '--table=backend']);
  is(r.error, null, 'query threw: ' + (r.error && r.error.message));
  ok(r.output, 'stdout is not JSON');
  is(r.output.data.length, 0);
});

test('query --file SQL ending in a -- comment still returns JSON', async () => {
  const sql = BACKEND_SQL + '\n-- top subsystems';
  const r = await runKlickhaus(['query', '--file=/q.sql'], { files: { '/q.sql': sql } });
  is(r.error, null, 'query threw: ' + (r.error && r.error.message));
  ok(r.output, 'stdout is not JSON');
  is(r.output.data[0].subsystem, 'SIDuP3HxleUgBDR3Gi8T24');
});

test('piped SQL is used when only flags are passed', async () => {
  const r = await runKlickhaus(['query', '--table=backend'], { stdin: BACKEND_SQL });
  is(r.error, null, 'query threw: ' + (r.error && r.error.message));
  is(r.requests.length, 1);
  ok(r.requests[0].sql.startsWith('SELECT subsystem'), 'sent: ' + r.requests[0].sql);
});

test('every table returns JSON through query --table=', async () => {
  const tables = ['delivery', 'admin', 'backend', 'da', 'lambda_logs', 'lambda_facet_minutes', 'site_configs', 'profile_configs', 'releases'];
  for (const table of tables) {
    const rows = [{ id: 'unquoted-' + table + '-id', c: 1 }];
    const sql = 'SELECT id, count() c FROM ' + table + ' GROUP BY id LIMIT 1';
    const r = await runKlickhaus(['query', sql, '--table=' + table], { rows });
    is(r.error, null, table + ': ' + (r.error && r.error.message));
    ok(r.output, table + ': stdout is not JSON');
    is(r.output && r.output.data[0].id, 'unquoted-' + table + '-id', table);
  }
});

test('built-in commands still receive JSON', async () => {
  const rows = [{ host: 'example.com', total: '10', errors_5xx: '1', error_rate: '10' }];
  const r = await runKlickhaus(['errors', '--table=backend', '--range=15m'], { rows });
  is(r.error, null, 'errors threw: ' + (r.error && r.error.message));
  ok(r.output, 'stdout is not JSON');
  is(r.output.table, 'backend');
  is(r.output.by_host[0].host, 'example.com');
});
