// Behaviour tests for scripts/newrelic.jsh. Run from skills/newrelic/:
//
//   tst tests/newrelic.test.js
//
// The script runs inside an AsyncFunction with mocked require/process/console
// and a fake NerdGraph endpoint. The skill config carries an API key, so every
// query goes through `fetch` (key mode) and is recorded. No test ever reaches a
// real New Relic account: mutations are answered by the fake.

import test, { is, ok } from 'tst';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(__dirname, '../scripts/newrelic.jsh');
const source = fs.readFileSync(target, 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

class ExitError extends Error {
  constructor(code) {
    super('exit ' + code);
    this.exitCode = code;
  }
}

const GUID = 'MjQyOTMzNHxTWU5USHxNT05JVE9SfGFiY2RlZi0xMjM0LTU2Nzg';

// Split a list into NerdGraph-style pages keyed by cursor: page 0 has no
// cursor, page i is requested with cursor 'c' + i.
function pages(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

function cursorOf(query) {
  const m = query.match(/cursor: "([^"]+)"/);
  return m ? m[1] : null;
}

function pageFor(query, list, size) {
  const cursor = cursorOf(query);
  const idx = cursor ? parseInt(cursor.slice(1), 10) : 0;
  const all = pages(list, size);
  const items = all[idx] || [];
  return { items, nextCursor: idx + 1 < all.length ? 'c' + (idx + 1) : null };
}

// A fake NerdGraph. `world` holds monitors, credentials and issues; lists are
// served `pageSize` per page with a nextCursor, like the real API.
function fakeNerdGraph(requests, world) {
  const size = world.pageSize || 2;
  return async (url, init) => {
    const query = JSON.parse(init.body).query;
    requests.push({ url: String(url), query });
    let data;
    if (query.startsWith('mutation')) {
      const name = query.match(/^mutation \{ (\w+)\(/)[1];
      data = { [name]: { errors: [], monitor: { name: 'M', period: 'EVERY_5_MINUTES', status: 'ENABLED' } } };
    } else if (/entity\(guid:/.test(query)) {
      data = { actor: { entity: world.entity || null } };
    } else if (/synthetics \{ script/.test(query)) {
      data = { actor: { account: { synthetics: { script: { text: 'old script' } } } } };
    } else if (/entitySearch/.test(query)) {
      let list = world.monitors || [];
      if (/SECURE_CRED/.test(query)) list = world.credentials || [];
      const named = query.match(/name = '([^']+)'/);
      if (named) list = list.filter((e) => e.name === named[1]);
      const p = pageFor(query, list, size);
      data = { actor: { entitySearch: { results: { entities: p.items, nextCursor: p.nextCursor } } } };
    } else if (/aiIssues/.test(query)) {
      const p = pageFor(query, world.issues || [], size);
      data = { actor: { account: { aiIssues: { issues: { issues: p.items, nextCursor: p.nextCursor } } } } };
    } else if (/nrql\(query:/.test(query)) {
      data = { actor: { account: { nrql: { results: [] } } } };
    } else {
      return new Response(JSON.stringify({ errors: [{ message: 'unhandled in fake: ' + query }] }), { status: 200 });
    }
    return new Response(JSON.stringify({ data }), { status: 200 });
  };
}

async function runNewrelic(args, { world = {}, config = { apiKey: 'NRAK-TEST' }, files = {} } = {}) {
  const requests = [];
  const stdout = [];
  const stderr = [];
  const execs = [];
  const mocks = {
    'sliccy:skill': { config: async () => config },
    'sliccy:exec': async (cmd) => {
      execs.push(cmd);
      return { exitCode: 0, stdout: '', stderr: '' };
    },
    fs: {
      readFile: async (p) => {
        if (!Object.hasOwn(files, p)) throw new Error('ENOENT ' + p);
        return files[p];
      },
      writeFile: async () => {},
      mkdir: async () => {},
      rm: async () => {},
    },
  };
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
    log: (m) => stdout.push(String(m)),
    error: (m) => stderr.push(String(m)),
    warn: (m) => stderr.push(String(m)),
  };
  let error = null;
  try {
    await new AsyncFunction('require', 'process', 'console', 'fetch', source)(
      mockRequire, mockProcess, mockConsole, fakeNerdGraph(requests, world),
    );
  } catch (e) {
    error = e;
  }
  let output = null;
  try { output = JSON.parse(stdout.join('\n')); } catch (_) { /* leave null */ }
  return { error, requests, stdout, stderr, output, execs };
}

function monitors(n, type) {
  const out = [];
  for (let i = 0; i < n; i += 1) {
    out.push({ guid: 'guid-' + i, name: 'mon-' + i, accountId: 1, monitorType: type || 'SCRIPT_API' });
  }
  return out;
}

// --- Finding 1: pagination ---

test('monitors follows nextCursor and returns every page', async () => {
  const r = await runNewrelic(['monitors', '--json'], { world: { monitors: monitors(5) } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  ok(r.output, 'stdout is not JSON');
  is(r.output && r.output.length, 5);
  is(r.requests.length, 3);
  is(cursorOf(r.requests[0].query), null, 'first page must not send a cursor');
  is(cursorOf(r.requests[1].query), 'c1');
  is(cursorOf(r.requests[2].query), 'c2');
  ok(/results\(cursor: "c1"\) \{ nextCursor entities/.test(r.requests[1].query), r.requests[1].query);
});

test('credentials follows nextCursor', async () => {
  const credentials = [{ guid: 'g1', name: 'A' }, { guid: 'g2', name: 'B' }, { guid: 'g3', name: 'C' }];
  const r = await runNewrelic(['credentials', '--json'], { world: { credentials } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.output && r.output.map((c) => c.name).join(','), 'A,B,C');
  is(r.requests.length, 2);
  is(cursorOf(r.requests[1].query), 'c1');
});

test('issues follows aiIssues nextCursor and keeps the state filter', async () => {
  const issues = [1, 2, 3, 4].map((i) => ({ issueId: 'i' + i, title: ['t' + i], state: 'ACTIVATED', priority: 'HIGH' }));
  const r = await runNewrelic(['issues', '--json'], { world: { issues } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.output && r.output.map((i) => i.issueId).join(','), 'i1,i2,i3,i4');
  is(r.requests.length, 2);
  ok(/issues\(cursor: "c1", filter: \{states: ACTIVATED\}\) \{ nextCursor issues/.test(r.requests[1].query), r.requests[1].query);
});

test('name lookup counts matches across pages', async () => {
  // The name filter in the fake leaves one match; put it past the first page by
  // making every monitor share the name, so ambiguity is detected across pages.
  const list = monitors(3).map((m) => Object.assign(m, { name: 'dup' }));
  const r = await runNewrelic(['monitor', 'dup'], { world: { monitors: list } });
  ok(r.error && r.error.exitCode === 1, 'expected exit 1');
  ok(r.stderr.join('\n').includes('3 matches'), 'stderr: ' + r.stderr.join('\n'));
});

test('pagination stops at the page cap and says so on stderr', async () => {
  const r = await runNewrelic(['monitors', '--json'], { world: { monitors: monitors(60), pageSize: 1 } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  is(r.requests.length, 50);
  is(r.output && r.output.length, 50);
  ok(/stopped after 50 pages/.test(r.stderr.join('\n')), 'stderr: ' + r.stderr.join('\n'));
});

// --- Finding 2: per-type update mutations ---

const EXPECTED = {
  SIMPLE: 'syntheticsUpdateSimpleMonitor',
  BROWSER: 'syntheticsUpdateSimpleBrowserMonitor',
  SCRIPT_BROWSER: 'syntheticsUpdateScriptBrowserMonitor',
  SCRIPT_API: 'syntheticsUpdateScriptApiMonitor',
};

test('set-period sends the mutation that matches the monitor type', async () => {
  for (const type of Object.keys(EXPECTED)) {
    const r = await runNewrelic(['set-period', 'mon-0', 'EVERY_5_MINUTES', '--confirm'],
      { world: { monitors: monitors(1, type) } });
    is(r.error, null, type + ' threw: ' + (r.error && r.error.message) + ' ' + r.stderr.join(' '));
    const last = r.requests[r.requests.length - 1].query;
    ok(last.startsWith('mutation { ' + EXPECTED[type] + '(guid: "guid-0", monitor: { period: EVERY_5_MINUTES })'),
      type + ' sent: ' + last);
  }
});

test('set-period by guid reads the type from the entity', async () => {
  const r = await runNewrelic(['set-period', GUID, 'EVERY_HOUR', '--confirm'],
    { world: { entity: { guid: GUID, name: 'M', accountId: 1, monitorType: 'BROWSER' } } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message));
  ok(/monitorType/.test(r.requests[0].query), 'entity lookup must ask for monitorType');
  ok(r.requests[1].query.startsWith('mutation { syntheticsUpdateSimpleBrowserMonitor('), r.requests[1].query);
});

test('set-script uses the script mutation for SCRIPT_BROWSER, after the backup read', async () => {
  const r = await runNewrelic(['set-script', 'mon-0', '--file=/s.js', '--confirm'],
    { world: { monitors: monitors(1, 'SCRIPT_BROWSER') }, files: { '/s.js': 'new script' } });
  is(r.error, null, 'threw: ' + (r.error && r.error.message) + ' ' + r.stderr.join(' '));
  is(r.requests.length, 3);
  ok(/synthetics \{ script/.test(r.requests[1].query), 'backup read second');
  ok(r.requests[2].query.startsWith('mutation { syntheticsUpdateScriptBrowserMonitor('), r.requests[2].query);
});

test('set-script refuses SIMPLE and BROWSER monitors without sending a mutation', async () => {
  for (const type of ['SIMPLE', 'BROWSER']) {
    const r = await runNewrelic(['set-script', 'mon-0', '--file=/s.js', '--confirm'],
      { world: { monitors: monitors(1, type) }, files: { '/s.js': 'x' } });
    ok(r.error && r.error.exitCode === 1, type + ': expected exit 1');
    ok(!r.requests.some((q) => q.query.startsWith('mutation')), type + ': a mutation was sent');
    ok(/has no script/.test(r.stderr.join('\n')), type + ' stderr: ' + r.stderr.join('\n'));
  }
});

test('set-period refuses an unsupported monitor type', async () => {
  const r = await runNewrelic(['set-period', 'mon-0', 'EVERY_HOUR', '--confirm'],
    { world: { monitors: monitors(1, 'CERT_CHECK') } });
  ok(r.error && r.error.exitCode === 1, 'expected exit 1');
  ok(!r.requests.some((q) => q.query.startsWith('mutation')), 'a mutation was sent');
});

// --- Finding 3: argument validation before network ---

test('checks and requests reject a bad --range before any request', async () => {
  for (const cmd of ['checks', 'requests']) {
    const r = await runNewrelic([cmd, 'mon-0', '--range=2h'], { world: { monitors: monitors(1) } });
    ok(r.error && r.error.exitCode === 1, cmd + ': expected exit 1');
    is(r.requests.length, 0, cmd + ': made ' + r.requests.length + ' requests');
    ok(/Unknown range: 2h/.test(r.stderr.join('\n')), cmd + ' stderr: ' + r.stderr.join('\n'));
  }
});

// --- Finding 4: docs ---

test('help documents --failed and scopes --limit to requests', async () => {
  const r = await runNewrelic(['--help']);
  const help = r.stdout.join('\n');
  ok(/--failed\s+`checks`/.test(help), 'help lacks --failed');
  ok(/--limit=N\s+Row cap for `requests` only/.test(help), 'help --limit not scoped');
  const skillMd = fs.readFileSync(path.resolve(__dirname, '../SKILL.md'), 'utf8');
  ok(skillMd.includes('- `--failed` —'), 'SKILL.md lacks --failed');
  ok(skillMd.includes('- `--limit=N` — Row cap for `requests` only'), 'SKILL.md --limit not scoped');
});

// --- Tab mode without a tab ---

test('no API key and no tab gives the clean tab error', async () => {
  const r = await runNewrelic(['monitors'], { config: {} });
  ok(r.error && r.error.exitCode === 1, 'expected exit 1');
  is(r.requests.length, 0);
  ok(r.stderr.join('\n').startsWith('No one.newrelic.com tab found'), 'stderr: ' + r.stderr.join('\n'));
});
