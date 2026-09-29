// Behaviour tests for scripts/cost-projection.jsh. Run from skills/cost-projection/:
//
//   tst tests/cost-projection.test.js
//
// The script runs inside an AsyncFunction with mocked require/process/console.
// It is pure local computation (no network), so the fake fetch only records
// that it was never called. The mock parseFlags models the one behaviour the
// script depends on: a valueless flag takes the following token as its value.

import test, { is, ok } from 'tst';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(__dirname, '../scripts/cost-projection.jsh');
const source = fs.readFileSync(target, 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

class NodeExitError extends Error {
  constructor(code, msg) {
    super(msg || 'exit ' + code);
    this.name = 'NodeExitError';
    this.exitCode = code;
  }
}

function parseFlags(tokens) {
  const flags = {};
  const positional = [];
  for (let i = 0; i < tokens.length; i++) {
    const tok = tokens[i];
    if (tok.startsWith('--')) {
      const eq = tok.indexOf('=');
      let name;
      let value;
      if (eq > 0) {
        name = tok.slice(2, eq);
        value = tok.slice(eq + 1);
      } else {
        name = tok.slice(2);
        const next = tokens[i + 1];
        if (next !== undefined && !next.startsWith('--')) {
          value = next;
          i++;
        } else {
          value = true;
        }
      }
      if (flags[name] === undefined) flags[name] = value;
      else flags[name] = [].concat(flags[name], value);
    } else {
      positional.push(tok);
    }
  }
  return { subcommand: positional[0], flags, positional };
}

async function runTool(args, files = {}) {
  const stdout = [];
  const stderr = [];
  const fetches = [];
  const argv = ['node', target, ...args];
  argv.parseFlags = () => parseFlags(args);
  const mocks = {
    fs: {
      exists: async (p) => Object.hasOwn(files, p),
      readFile: async (p) => {
        if (!Object.hasOwn(files, p)) throw new Error('ENOENT ' + p);
        return files[p];
      },
    },
    'sliccy:cli': {
      die: (msg) => {
        stderr.push(String(msg));
        throw new NodeExitError(1, String(msg));
      },
      out: (obj) => stdout.push(JSON.stringify(obj, null, 2)),
      help: (text) => {
        stdout.push(text);
        throw new NodeExitError(0);
      },
    },
    'sliccy:color': new Proxy({}, { get: () => (s) => String(s) }),
  };
  const mockRequire = (id) => {
    if (Object.hasOwn(mocks, id)) return mocks[id];
    throw new Error('module not available in test: ' + id);
  };
  const mockProcess = { argv, env: {}, exit: (code) => { throw new NodeExitError(code); } };
  const mockConsole = {
    log: (m = '') => stdout.push(String(m)),
    error: (m = '') => stderr.push(String(m)),
    warn: (m = '') => stderr.push(String(m)),
  };
  const fakeFetch = async (url) => {
    fetches.push(String(url));
    throw new Error('network is not allowed in cost-projection');
  };
  let error = null;
  try {
    await new AsyncFunction('require', 'process', 'console', 'fetch', source)(
      mockRequire, mockProcess, mockConsole, fakeFetch,
    );
  } catch (e) {
    error = e;
  }
  let output = null;
  try { output = JSON.parse(stdout.join('\n')); } catch (_) { /* leave null */ }
  return { error, stdout, stderr, output, fetches };
}

async function example(which) {
  const r = await runTool(['example', which]);
  return r.stdout.join('\n');
}

// ── finding 1: fit withholds the stanza across a same-sign regime break ──

test('fit withholds the compute_authoring stanza: level break with same-sign growth', async () => {
  const files = { '/s.json': await example('series') };
  const r = await runTool(['fit', '/s.json', '--json'], files);
  is(r.error, null, 'fit threw: ' + (r.error && r.error.message));
  const ca = r.output.series.find((s) => s.name === 'compute_authoring');
  // Precondition: both growth figures negative, so the sign-flip guard is silent.
  ok(ca.growth_full < 0 && ca.growth_6m < 0, 'growth signs: ' + ca.growth_full + ' / ' + ca.growth_6m);
  is(ca.suggestion_safe, false, 'stanza must be withheld');
  ok(typeof ca.suggested_component.unsafe === 'string', 'stanza carries an unsafe label');
  is(ca.regime_break && ca.regime_break.at, '2026-02');
  is(ca.regime_break && ca.regime_break.t_stat, -7.6);
  const w = ca.warnings.find((x) => /regime break/.test(x)) || '';
  ok(/detect-breaks/.test(w) && /--from 2026-02/.test(w), 'warning points at detect-breaks/--from: ' + w);
  is(r.fetches.length, 0);
});

test('fit keeps clean series and the post-break window pasteable', async () => {
  const files = { '/s.json': await example('series') };
  const full = await runTool(['fit', '/s.json', '--json'], files);
  const byName = Object.fromEntries(full.output.series.map((s) => [s.name, s]));
  is(byName.edge_variable.suggestion_safe, true, 'edge_variable (partial month flagged) stays safe');
  is(byName.cdn_fixed.suggestion_safe, true, 'cdn_fixed (flat) stays safe');
  is(byName.cdn_fixed.regime_break, null);
  const post = await runTool(['fit', '/s.json', '--from', '2026-02', '--json'], files);
  const ca = post.output.series.find((s) => s.name === 'compute_authoring');
  is(ca.suggestion_safe, true, 'post-break window is pasteable');
  is(ca.regime_break, null);
  is(JSON.stringify(ca.suggested_component), '{"name":"compute_authoring","base":8750,"g_mu":0.041,"g_sd":0.088,"sig_m":0.025}');
});

test('fit human report says withheld and names the break month', async () => {
  const files = { '/s.json': await example('series') };
  const r = await runTool(['fit', '/s.json'], files);
  const text = r.stdout.join('\n');
  const block = text.slice(text.indexOf('compute_authoring'), text.indexOf('cdn_fixed'));
  ok(/suggested → withheld/.test(block), block);
  ok(/regime break inside the fit window: step down ÷1\.55 at 2026-02 \(t=-7\.6\)/.test(block), block);
});

test('detect-breaks output is unchanged by the shared scan', async () => {
  const files = { '/s.json': await example('series') };
  const r = await runTool(['detect-breaks', '/s.json', '--json'], files);
  const ca = r.output.series.find((s) => s.name === 'compute_authoring');
  is(ca.recommendation.from, '2026-02');
  is(ca.breaks.length, 2);
  is(ca.breaks[0].t_stat, -7.6);
  is(ca.breaks[1].at, '2026-03');
  is(ca.breaks[1].t_stat, -4.14);
  is(ca.recommendation.post_break_months, 6);
});

// ── finding 2: fiscal vs calendar share draws (common random numbers) ──

const ONE = JSON.stringify({
  name: 'one', base_month: '2026-08', fiscal_year_start: 12, horizon_months: 12,
  components: [{ name: 'a', base: 10000, g_mu: 0.2, g_sd: 0.3, sig_m: 0 }],
});

// With sig_m 0 the fiscal total is a monotone function of the year-one growth
// draw g1, so its P50 pins the sample-median g1. On shared paths the calendar
// window drops month 1 and adds month 13 of those same paths, so the delta must
// sit next to base*(exp(g1/24) - exp(g1 + g_mu/24)) at that g1. Independent
// streams scatter it by hundreds of dollars (mean |residual| ~$96 at 20k runs).
function predictedDelta(p50) {
  const base = 10000;
  const F = (g) => { let t = 0; for (let m = 1; m <= 12; m++) t += base * Math.exp((g * (m - 0.5)) / 12); return t; };
  let lo = -3;
  let hi = 3;
  for (let i = 0; i < 200; i++) { const mid = (lo + hi) / 2; if (F(mid) < p50) lo = mid; else hi = mid; }
  return base * (Math.exp(lo / 24) - Math.exp(lo + 0.2 / 24));
}

test('fiscal-vs-calendar delta is computed on shared paths (common random numbers)', async () => {
  const files = { '/c.json': ONE };
  const residuals = [];
  for (const seed of [1, 2, 3, 4, 5, 6]) {
    const r = await runTool(['simulate', '/c.json', '--runs', '20000', '--seed', String(seed), '--json'], files);
    is(r.error, null, 'simulate threw: ' + (r.error && r.error.message));
    residuals.push(Math.abs(r.output.fiscal_vs_calendar.delta_abs - predictedDelta(r.output.total.p50)));
  }
  const meanAbs = residuals.reduce((x, y) => x + y, 0) / residuals.length;
  ok(meanAbs < 30, 'mean |delta - paired prediction| = $' + meanAbs.toFixed(1) + ' (' + residuals.map((x) => x.toFixed(0)).join(', ') + ')');
});

test('adding the calendar comparison does not change the fiscal numbers', async () => {
  const withCmp = JSON.parse(ONE);
  const without = { ...withCmp, fiscal_year_start: 1, start: '2026-12' };
  const a = await runTool(['simulate', '/a.json', '--runs', '5000', '--json'], { '/a.json': JSON.stringify(withCmp) });
  const b = await runTool(['simulate', '/b.json', '--runs', '5000', '--json'], { '/b.json': JSON.stringify(without) });
  ok(a.output.fiscal_vs_calendar, 'comparison present');
  is(b.output.fiscal_vs_calendar, null);
  is(JSON.stringify(a.output.total), JSON.stringify(b.output.total));
});

test('a window already starting in January compares to itself (zero shift)', async () => {
  const cfg = { ...JSON.parse(ONE), start: '2027-01' };
  const r = await runTool(['simulate', '/c.json', '--runs', '2000', '--json'], { '/c.json': JSON.stringify(cfg) });
  is(r.error, null, 'simulate threw: ' + (r.error && r.error.message));
  is(r.output.fiscal_vs_calendar.shift_months, 0);
  is(r.output.fiscal_vs_calendar.delta_abs, 0);
});

test('worked example: default seed is deterministic and matches example.md', async () => {
  const files = { '/c.json': await example('config') };
  const a = await runTool(['simulate', '/c.json', '--json'], files);
  const b = await runTool(['simulate', '/c.json', '--json'], files);
  is(a.stdout.join('\n'), b.stdout.join('\n'), 'byte-identical across runs');
  is(Math.round(a.output.total.p50), 469795);
  is(Math.round(a.output.total.p10), 424083);
  is(Math.round(a.output.total.p90), 528400);
  is(Math.round(a.output.fiscal_vs_calendar.calendar.p50), 475687);
  is(Math.round(a.output.fiscal_vs_calendar.delta_abs), -5892);
});
