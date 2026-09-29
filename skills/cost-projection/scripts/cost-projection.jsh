// cost-projection.jsh — probabilistic forward projection of measured cost series.
// Pure computation: reads JSON series/config from the VFS, writes percentiles.
// No network, no browser session, no credentials.
const fs = require('fs');
const cli = require('sliccy:cli');
const color = require('sliccy:color');

const TOOL = 'cost-projection';

const HELP = `
${TOOL} — Monte Carlo forward projection from measured monthly cost series

USAGE
  ${TOOL} fit <series.json> [--from YYYY-MM] [--explain] [--include-partial]
  ${TOOL} detect-breaks <series.json> [--threshold R] [--top N]
  ${TOOL} simulate <config.json> [--runs N] [--seed N] [--fiscal-year-start M]
  ${TOOL} variance <config.json> [--runs N] [--seed N]
  ${TOOL} scenario <config.json> --toggle <name>=<prob> [--toggle ...]
  ${TOOL} example [config|series]

FLAGS
  --runs N               Simulation paths (default 200000)
  --seed N               PRNG seed for a reproducible run (default 20260827)
  --from YYYY-MM         Fit only points from this month onward (post-break window)
  --threshold R          detect-breaks: segment-mean ratio to flag (default 1.5)
  --top N                detect-breaks: how many candidate breaks to list (default 3)
  --fiscal-year-start M   First calendar month of the fiscal year, 1-12 (e.g. 12 for a Dec-Nov FY)
  --growth-draw MODE     per-year (default) or per-month. per-month reproduces the
                         classic too-narrow band and is for auditing only.
  --include-partial      Include points marked partial/estimated in a fit (unsafe)
  --explain              Print the recorded damping rationale for each series/component
  --json                 Emit raw JSON instead of the human report

NOTES
  Growth uncertainty is drawn ONCE PER SIMULATED YEAR, not per month. Monthly
  jitter averages down by sqrt(12) and barely widens an annual total. See
  references/methodology.md.
  Put the file argument BEFORE valueless flags: "fit s.json --json", because
  "--json s.json" makes the parser read the path as the flag's value.
`.trim();

// ── args ──────────────────────────────────────────────────────────────
const parsed = process.argv.parseFlags();
const subcommand = parsed.subcommand || '';
const flags = parsed.flags;
// parseFlags gives a valueless flag the following token as its value, so
// "--json cfg.json" lands the path in flags.json. Reclaim it as a positional
// rather than failing with a confusing "missing argument".
const BOOLEAN_FLAGS = ['json', 'explain', 'include-partial', 'help', 'h'];
const positional = parsed.positional.slice(1);
for (const name of BOOLEAN_FLAGS) {
  const v = flags[name];
  if (typeof v === 'string' && /\.json$/i.test(v)) {
    positional.push(v);
    flags[name] = true;
  }
}

function num(value, fallback) {
  // Number(), not parseInt(): "2e5" must mean 200000, not 2.
  if (value === undefined || value === true) return fallback;
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

const RUNS = Math.min(Math.max(Math.round(num(flags.runs, 200000)), 1000), 5000000);
const SEED = Math.round(num(flags.seed, 20260827));
const JSON_OUT = flags.json === true;

// per-year is the correct model. per-month exists so a reader can reproduce the
// failure mode it replaced: iid monthly growth draws average down by sqrt(12)
// and collapse the annual band to a falsely precise width.
const GROWTH_DRAW = (() => {
  const v = flags['growth-draw'];
  if (v === undefined) return 'per-year';
  const mode = String(v).toLowerCase();
  if (mode !== 'per-year' && mode !== 'per-month') {
    cli.die(`--growth-draw wants per-year or per-month, got ${JSON.stringify(v)}`, { prefix: TOOL });
  }
  return mode;
})();

// ── deterministic PRNG ────────────────────────────────────────────────
function mulberry32(seed) {
  let a = seed | 0;
  return function () {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Marsaglia polar method; caches the second variate.
function makeNormal(rng) {
  let spare = null;
  return function () {
    if (spare !== null) {
      const v = spare;
      spare = null;
      return v;
    }
    let u, v, r;
    do {
      u = 2 * rng() - 1;
      v = 2 * rng() - 1;
      r = u * u + v * v;
    } while (r === 0 || r >= 1);
    const f = Math.sqrt((-2 * Math.log(r)) / r);
    spare = v * f;
    return u * f;
  };
}

// ── small helpers ─────────────────────────────────────────────────────
const MONTH_RE = /^(\d{4})-(\d{2})$/;

function monthIndex(month) {
  const m = MONTH_RE.exec(String(month || '').trim());
  if (!m) return null;
  const year = Number(m[1]);
  const mon = Number(m[2]);
  if (mon < 1 || mon > 12) return null;
  return year * 12 + (mon - 1);
}

function monthLabel(index) {
  const year = Math.floor(index / 12);
  const mon = (index % 12) + 1;
  return `${year}-${String(mon).padStart(2, '0')}`;
}

function usd(v) {
  const sign = v < 0 ? '-' : '';
  return `${sign}$${Math.round(Math.abs(v)).toLocaleString('en-US')}`;
}

function pctStr(v, digits = 1) {
  if (v === null || v === undefined || !Number.isFinite(v)) return 'n/a';
  return `${v >= 0 ? '+' : ''}${(v * 100).toFixed(digits)}%`;
}

function percentile(sorted, p) {
  const i = (sorted.length - 1) * p;
  const lo = Math.floor(i);
  const hi = Math.ceil(i);
  return lo === hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
}

function mean(xs) {
  let s = 0;
  for (const x of xs) s += x;
  return xs.length ? s / xs.length : 0;
}

function stdev(xs) {
  if (xs.length < 2) return 0;
  const m = mean(xs);
  let s = 0;
  for (const x of xs) s += (x - m) * (x - m);
  return Math.sqrt(s / (xs.length - 1));
}

const RULE = color.dim(`  ${'─'.repeat(64)}`);

async function loadJson(pathArg, what) {
  if (!pathArg) cli.die(`missing <${what}> — run '${TOOL} --help'`, { prefix: TOOL });
  if (!(await fs.exists(pathArg))) cli.die(`file not found: ${pathArg}`, { prefix: TOOL });
  let text;
  try {
    text = await fs.readFile(pathArg);
  } catch (err) {
    cli.die(`cannot read ${pathArg}: ${err.message}`, { prefix: TOOL });
  }
  try {
    return JSON.parse(text);
  } catch (err) {
    cli.die(`${pathArg} is not valid JSON: ${err.message}`, { prefix: TOOL });
  }
}

// ── series normalisation ──────────────────────────────────────────────
// Accepts: {series:[...]} | [series,...] | {name,points} | {points} |
// points as an array of {month,amount,...} or as a {"YYYY-MM": amount} map.
function normalizePoints(raw, seriesName) {
  let list = [];
  if (Array.isArray(raw)) {
    list = raw.map((p) => ({ ...p }));
  } else if (raw && typeof raw === 'object') {
    list = Object.entries(raw).map(([month, v]) =>
      v && typeof v === 'object' ? { month, ...v } : { month, amount: v }
    );
  }
  const points = [];
  for (const p of list) {
    const idx = monthIndex(p.month);
    if (idx === null) {
      cli.die(`series "${seriesName}": bad month ${JSON.stringify(p.month)} (want YYYY-MM)`, {
        prefix: TOOL,
      });
    }
    const amount = Number(p.amount ?? p.cost ?? p.value);
    if (!Number.isFinite(amount)) {
      cli.die(`series "${seriesName}": point ${p.month} has no numeric amount`, { prefix: TOOL });
    }
    points.push({
      month: p.month,
      idx,
      amount,
      partial: p.partial === true,
      estimated: p.estimated === true,
      note: p.note || '',
    });
  }
  points.sort((a, b) => a.idx - b.idx);
  return points;
}

function normalizeSeriesFile(raw, pathArg) {
  let entries;
  if (Array.isArray(raw)) entries = raw;
  else if (raw && Array.isArray(raw.series)) entries = raw.series;
  else if (raw && (raw.points || raw.months)) entries = [raw];
  else if (raw && typeof raw === 'object') {
    // bare {name: points} map
    entries = Object.entries(raw).map(([name, points]) => ({ name, points }));
  } else {
    cli.die(`${pathArg}: cannot find any series`, { prefix: TOOL });
  }
  const out = entries.map((s, i) => {
    const name = s.name || s.component || `series_${i + 1}`;
    return {
      name,
      unit: s.unit || s.currency || 'USD',
      damping: s.damping || null,
      note: s.note || '',
      points: normalizePoints(s.points || s.months || [], name),
    };
  });
  if (!out.length) cli.die(`${pathArg}: no series found`, { prefix: TOOL });
  return out;
}

// ── fit ───────────────────────────────────────────────────────────────
function fitSeries(series, opts = {}) {
  const fromIdx = opts.from ? monthIndex(opts.from) : null;
  if (opts.from && fromIdx === null) {
    cli.die(`--from ${opts.from} is not a YYYY-MM month`, { prefix: TOOL });
  }
  const excluded = [];
  const used = [];
  for (const p of series.points) {
    if (fromIdx !== null && p.idx < fromIdx) {
      excluded.push({ ...p, reason: `before --from ${opts.from}` });
      continue;
    }
    if (!opts.includePartial && (p.partial || p.estimated)) {
      excluded.push({ ...p, reason: p.partial ? 'partial billing month' : 'estimated value' });
      continue;
    }
    if (p.amount <= 0) {
      excluded.push({ ...p, reason: 'non-positive amount (cannot take a log)' });
      continue;
    }
    used.push(p);
  }

  const result = {
    name: series.name,
    n_used: used.length,
    n_excluded: excluded.length,
    excluded: excluded.map((p) => ({ month: p.month, amount: p.amount, reason: p.reason })),
    window: used.length ? { from: used[0].month, to: used[used.length - 1].month } : null,
    damping: series.damping || null,
    note: series.note || '',
  };
  if (used.length < 2) {
    result.insufficient = true;
    result.mean_level = used.length ? used[0].amount : null;
    return result;
  }

  const levels = used.map((p) => p.amount);
  const m = mean(levels);
  const sd = stdev(levels);
  result.mean_level = m;
  result.sd_level = sd;
  result.cv = m > 0 ? sd / m : null;
  result.min_level = Math.min(...levels);
  result.max_level = Math.max(...levels);
  result.last_level = levels[levels.length - 1];

  // Month-over-month log returns from ADJACENT months only. Treating a gap as
  // one step inflates the drift and understates the volatility.
  const logrets = [];
  let gaps = 0;
  for (let i = 1; i < used.length; i++) {
    const step = used[i].idx - used[i - 1].idx;
    if (step === 1) logrets.push(Math.log(used[i].amount / used[i - 1].amount));
    else gaps++;
  }
  result.n_logret_pairs = logrets.length;
  result.n_gaps_skipped = gaps;
  result.mom_log_mean = logrets.length ? mean(logrets) : null;
  result.mom_log_sd = logrets.length > 1 ? stdev(logrets) : null;

  const spanMonths = used[used.length - 1].idx - used[0].idx;
  result.span_months = spanMonths;
  result.growth_full = spanMonths > 0 ? (levels[levels.length - 1] / levels[0]) ** (12 / spanMonths) - 1 : null;
  result.growth_drift =
    result.mom_log_mean === null ? null : Math.exp(12 * result.mom_log_mean) - 1;

  // trailing 6 months (endpoint-to-endpoint, annualised)
  const lastIdx = used[used.length - 1].idx;
  const recent = used.filter((p) => p.idx >= lastIdx - 6);
  if (recent.length >= 2) {
    const rSpan = recent[recent.length - 1].idx - recent[0].idx;
    result.growth_6m =
      rSpan > 0 ? (recent[recent.length - 1].amount / recent[0].amount) ** (12 / rSpan) - 1 : null;
    result.growth_6m_window = { from: recent[0].month, to: recent[recent.length - 1].month };
  } else {
    result.growth_6m = null;
    result.growth_6m_window = null;
  }

  // 12-month-ahead level uncertainty implied by the monthly volatility.
  result.implied_12m_uncertainty =
    result.mom_log_sd === null ? null : result.mom_log_sd * Math.sqrt(12);

  // A ready-to-paste component stanza for simulate.
  const gMu = result.growth_6m !== null ? Math.log(1 + Math.max(result.growth_6m, -0.95)) : 0;
  result.suggested_component = {
    name: series.name,
    base: Math.round(result.last_level),
    g_mu: Number.isFinite(gMu) ? Number(gMu.toFixed(3)) : 0,
    g_sd: result.implied_12m_uncertainty === null
      ? 0.3
      : Number(Math.max(result.implied_12m_uncertainty, 0.05).toFixed(3)),
    sig_m: result.mom_log_sd === null ? 0 : Number(result.mom_log_sd.toFixed(3)),
  };

  const warnings = [];
  if (result.growth_full !== null && Math.abs(result.growth_full) > 5) {
    warnings.push(
      `full-window annualised growth is ${pctStr(result.growth_full, 0)} — implausible for a cost series; run '${TOOL} detect-breaks' before trusting any fit`
    );
  }
  if (
    result.growth_full !== null &&
    result.growth_6m !== null &&
    Math.sign(result.growth_full) !== Math.sign(result.growth_6m) &&
    Math.abs(result.growth_full - result.growth_6m) > 0.25
  ) {
    warnings.push(
      `full-window (${pctStr(result.growth_full)}) and trailing-6m (${pctStr(result.growth_6m)}) growth disagree in SIGN — a regime break is likely; pick a post-break window with --from`
    );
  }
  if (result.cv !== null && result.cv === 0) {
    warnings.push('CV is exactly 0.0% — this looks like a fixed contractual line: model it with sig_m 0 and a renewal-uplift g_sd only');
  }
  if (result.n_gaps_skipped > 0) {
    warnings.push(`${result.n_gaps_skipped} non-adjacent month gap(s) skipped in the volatility estimate`);
  }
  if (result.implied_12m_uncertainty !== null && result.implied_12m_uncertainty > 1.0) {
    warnings.push(
      `implied 12-month uncertainty is ${pctStr(result.implied_12m_uncertainty, 0)} — almost certainly contaminated by a break or a partial month; damp it and record a 'why'`
    );
  }
  result.warnings = warnings;
  // A suggestion derived from a break-contaminated window is worse than no
  // suggestion, so label it rather than let it be pasted into a config.
  const contaminated =
    (result.growth_full !== null && Math.abs(result.growth_full) > 5) ||
    (result.implied_12m_uncertainty !== null && result.implied_12m_uncertainty > 1.0) ||
    (result.growth_full !== null &&
      result.growth_6m !== null &&
      Math.sign(result.growth_full) !== Math.sign(result.growth_6m) &&
      Math.abs(result.growth_full - result.growth_6m) > 0.25);
  result.suggestion_safe = !contaminated;
  if (contaminated) {
    result.suggested_component.unsafe =
      "derived from a window that looks break-contaminated — run '" +
      TOOL +
      " detect-breaks' and refit with --from before using these values";
  }
  return result;
}

function printFit(f, explain) {
  console.log('');
  console.log(`  ${color.cyan(color.bold(f.name))}  ${color.dim(f.window ? `${f.window.from} → ${f.window.to}` : 'no usable points')}`);
  if (f.insufficient) {
    console.log(color.dim('    fewer than 2 usable points — cannot fit'));
    if (f.excluded.length) for (const e of f.excluded) console.log(color.dim(`    excluded ${e.month}: ${e.reason}`));
    return;
  }
  console.log(`    months used      ${f.n_used}${f.n_excluded ? color.dim(`  (${f.n_excluded} excluded)`) : ''}`);
  console.log(`    mean level       ${usd(f.mean_level)}   ${color.dim(`min ${usd(f.min_level)} · max ${usd(f.max_level)} · last ${usd(f.last_level)}`)}`);
  console.log(`    CV (level)       ${(f.cv * 100).toFixed(1)}%`);
  console.log(`    m/m log mean     ${f.mom_log_mean === null ? 'n/a' : f.mom_log_mean.toFixed(4)}   ${color.dim(`from ${f.n_logret_pairs} adjacent pair(s)`)}`);
  console.log(`    m/m log sd       ${f.mom_log_sd === null ? 'n/a' : f.mom_log_sd.toFixed(4)}`);
  console.log(`    growth (full)    ${pctStr(f.growth_full)}/yr  ${color.dim(`over ${f.span_months} months`)}`);
  console.log(`    growth (last 6m) ${pctStr(f.growth_6m)}/yr  ${color.dim(f.growth_6m_window ? `${f.growth_6m_window.from} → ${f.growth_6m_window.to}` : '')}`);
  console.log(`    implied 12m band ${f.implied_12m_uncertainty === null ? 'n/a' : `±${(f.implied_12m_uncertainty * 100).toFixed(0)}%`}   ${color.dim('= m/m log sd × sqrt(12)')}`);
  const s = f.suggested_component;
  if (f.suggestion_safe) {
    console.log(`    ${color.dim('suggested →')} ${color.dim(JSON.stringify(s))}`);
  } else {
    console.log(`    ${color.red('suggested → withheld')} ${color.dim('(break-contaminated window)')}`);
    const bare = { name: s.name, base: s.base, g_mu: s.g_mu, g_sd: s.g_sd, sig_m: s.sig_m };
    console.log(color.dim(`      would have been ${JSON.stringify(bare)}`));
    console.log(color.dim(`      run '${TOOL} detect-breaks <file>', then refit with --from <YYYY-MM>`));
  }
  for (const e of f.excluded) console.log(color.dim(`    excluded ${e.month} (${usd(e.amount)}): ${e.reason}`));
  if (explain) {
    if (f.damping) {
      const why = f.damping.why || color.red('NO why RECORDED');
      console.log(`    ${color.yellow('damping')}  measured ${pctStr(f.damping.measured_12m ?? f.damping.measured, 0)} → applied ${pctStr(f.damping.applied, 0)}`);
      console.log(`             ${color.dim('why:')} ${why}`);
    } else {
      console.log(color.dim('    damping   none recorded'));
    }
    if (f.note) console.log(`    ${color.dim('note:')} ${f.note}`);
  }
  for (const w of f.warnings) console.log(`    ${color.yellow('warn')} ${w}`);
}

async function cmdFit(positional, flags) {
  const raw = await loadJson(positional[0], 'series.json');
  const all = normalizeSeriesFile(raw, positional[0]);
  const fits = all.map((s) =>
    fitSeries(s, { from: typeof flags.from === 'string' ? flags.from : null, includePartial: flags['include-partial'] === true })
  );
  if (JSON_OUT) {
    cli.out({ command: 'fit', series: fits });
    return;
  }
  console.log('');
  console.log(`  ${color.bold('Fit')} ${color.dim(`— ${fits.length} series from ${positional[0]}`)}`);
  console.log(RULE);
  for (const f of fits) printFit(f, flags.explain === true);
  console.log('');
  console.log(color.dim(`  Paste the suggested stanzas into a config and run '${TOOL} simulate'.`));
  console.log(color.dim("  Widen g_sd rather than trusting a suspiciously clean series."));
}

// ── detect-breaks ─────────────────────────────────────────────────────
function detectBreaks(series, opts = {}) {
  const threshold = opts.threshold || 1.5;
  const top = opts.top || 3;
  // Suspect detection runs over every positive point (it hunts for partial
  // months the analyst forgot to flag). The break scan runs over CLEAN points
  // only — an included partial month invents a phantom step down at the tail.
  const positive = series.points.filter((p) => p.amount > 0);
  const usable = positive.filter((p) => !p.partial && !p.estimated);
  const quality = series.points
    .filter((p) => p.partial || p.estimated || p.amount <= 0)
    .map((p) => ({
      month: p.month,
      amount: p.amount,
      issue: p.partial
        ? 'marked partial — an incomplete billing period, not a decline'
        : p.estimated
          ? 'marked estimated'
          : 'non-positive amount',
    }));

  // Heuristic: a point far below the trailing median that is NOT flagged is
  // very often an unflagged partial month. This is the trap worth shouting about.
  const suspects = [];
  for (let i = 1; i < positive.length; i++) {
    const prior = positive.slice(Math.max(0, i - 6), i).map((p) => p.amount).sort((a, b) => a - b);
    if (prior.length < 3) continue;
    const med = prior[Math.floor(prior.length / 2)];
    const p = positive[i];
    if (!p.partial && !p.estimated && med > 0 && p.amount < med * 0.35) {
      suspects.push({
        month: p.month,
        amount: p.amount,
        trailing_median: med,
        ratio: p.amount / med,
        hint: i === positive.length - 1
          ? 'last point and far below trend — almost certainly an incomplete billing month; mark it partial:true'
          : 'far below the trailing median — check for a partial month or a credit',
      });
    }
  }

  const candidates = [];
  const MIN_SEG = 3;
  for (let k = MIN_SEG; k <= usable.length - MIN_SEG; k++) {
    const before = usable.slice(0, k).map((p) => p.amount);
    const after = usable.slice(k).map((p) => p.amount);
    const mb = mean(before);
    const ma = mean(after);
    if (mb <= 0 || ma <= 0) continue;
    const ratio = ma / mb;
    // Welch t on log levels: distinguishes a crisp modest step (a contract
    // change) from a large step inside a very noisy series.
    const lb = before.map(Math.log);
    const la = after.map(Math.log);
    const vb = lb.length > 1 ? stdev(lb) ** 2 / lb.length : 0;
    const va = la.length > 1 ? stdev(la) ** 2 / la.length : 0;
    const se = Math.sqrt(vb + va);
    const t = se > 0 ? (mean(la) - mean(lb)) / se : null;
    candidates.push({
      t_stat: t === null ? null : Number(t.toFixed(2)),
      at: usable[k].month,
      ratio,
      abs_log_ratio: Math.abs(Math.log(ratio)),
      mean_before: mb,
      mean_after: ma,
      n_before: before.length,
      n_after: after.length,
      direction: ratio > 1 ? 'step up' : 'step down',
    });
  }
  candidates.sort((a, b) => b.abs_log_ratio - a.abs_log_ratio);
  const logThreshold = Math.abs(Math.log(threshold));
  const breaks = candidates
    .filter(
      (c) =>
        c.abs_log_ratio >= logThreshold ||
        // crisp modest step: strongly significant AND at least 1.25x
        (c.t_stat !== null && Math.abs(c.t_stat) > 4 && c.abs_log_ratio >= Math.log(1.25))
    )
    .slice(0, top);

  const full = fitSeries(series, {});
  const out = {
    name: series.name,
    n_points: series.points.length,
    threshold,
    data_quality: quality,
    unflagged_suspects: suspects,
    breaks,
    candidates_considered: candidates.length,
    top_candidates: candidates.slice(0, top),
    full_window_growth: full.growth_full ?? null,
    recommendation: null,
  };
  if (breaks.length) {
    const strongest = breaks[0];
    const post = fitSeries(series, { from: strongest.at });
    out.recommendation = {
      from: strongest.at,
      reason: `${strongest.direction} of ${strongest.ratio >= 1 ? strongest.ratio.toFixed(2) : (1 / strongest.ratio).toFixed(2)}x in segment means at ${strongest.at}`,
      post_break_growth: post.growth_full ?? null,
      post_break_months: post.n_used,
      post_break_mean: post.mean_level ?? null,
      command: `${TOOL} fit ${opts.pathArg || '<series.json>'} --from ${strongest.at}`,
    };
  }
  return out;
}

function printBreaks(b) {
  console.log('');
  console.log(`  ${color.cyan(color.bold(b.name))}  ${color.dim(`${b.n_points} points · break threshold ${b.threshold}x`)}`);
  if (b.full_window_growth !== null) {
    const nonsense = Math.abs(b.full_window_growth) > 5;
    const txt = `    full-window growth  ${pctStr(b.full_window_growth, 0)}/yr`;
    console.log(nonsense ? `${txt}  ${color.red('← not a real growth rate')}` : txt);
  }
  if (b.data_quality.length) {
    console.log(`    ${color.yellow('flagged points (excluded from fits)')}`);
    for (const q of b.data_quality) console.log(`      ${q.month}  ${usd(q.amount)}  ${color.dim(q.issue)}`);
  }
  if (b.unflagged_suspects.length) {
    console.log(`    ${color.red('UNFLAGGED suspects')}`);
    for (const s of b.unflagged_suspects) {
      console.log(`      ${s.month}  ${usd(s.amount)} vs trailing median ${usd(s.trailing_median)} (${(s.ratio * 100).toFixed(0)}%)`);
      console.log(color.dim(`        ${s.hint}`));
    }
  }
  if (!b.breaks.length) {
    console.log(color.dim(`    no regime break at or above ${b.threshold}x (${b.candidates_considered} split points tested)`));
    if (b.top_candidates.length) {
      const c = b.top_candidates[0];
      console.log(color.dim(`    largest candidate: ${c.at} ${c.direction} ${c.ratio.toFixed(2)}x`));
    }
  } else {
    console.log(`    ${color.bold('regime breaks')}`);
    for (const c of b.breaks) {
      console.log(`      ${color.bold(c.at)}  ${c.direction} ${c.ratio >= 1 ? `${c.ratio.toFixed(2)}x` : `÷${(1 / c.ratio).toFixed(2)}`}   ${color.dim(`mean ${usd(c.mean_before)} (${c.n_before}m) → ${usd(c.mean_after)} (${c.n_after}m)${c.t_stat === null ? "" : ` · t=${c.t_stat}`}`)}`);
    }
    const r = b.recommendation;
    console.log('');
    console.log(`    ${color.green('→')} refit on the post-break window: ${color.bold(`--from ${r.from}`)}`);
    console.log(color.dim(`      ${r.reason}`));
    console.log(color.dim(`      post-break: ${r.post_break_months} months, mean ${usd(r.post_break_mean || 0)}, growth ${pctStr(r.post_break_growth)}/yr`));
    console.log(color.dim(`      ${r.command}`));
  }
}

async function cmdDetectBreaks(positional, flags) {
  const raw = await loadJson(positional[0], 'series.json');
  const all = normalizeSeriesFile(raw, positional[0]);
  const results = all.map((s) =>
    detectBreaks(s, {
      threshold: num(flags.threshold, 1.5),
      top: Math.round(num(flags.top, 3)),
      pathArg: positional[0],
    })
  );
  if (JSON_OUT) {
    cli.out({ command: 'detect-breaks', series: results });
    return;
  }
  console.log('');
  console.log(`  ${color.bold('Regime break scan')} ${color.dim(`— ${results.length} series`)}`);
  console.log(RULE);
  for (const r of results) printBreaks(r);
  console.log('');
  const flagged = results.filter(
    (r) => r.breaks.length || r.unflagged_suspects.length || r.data_quality.length
  ).length;
  console.log(color.dim(`  ${flagged} of ${results.length} series need a post-break window or a data fix.`));
}

// ── config normalisation ──────────────────────────────────────────────
function normalizeConfig(raw, pathArg) {
  if (!raw || typeof raw !== 'object') cli.die(`${pathArg}: config must be a JSON object`, { prefix: TOOL });
  const comps = raw.components || raw.component || [];
  if (!Array.isArray(comps) || !comps.length) {
    cli.die(`${pathArg}: config needs a non-empty "components" array`, { prefix: TOOL });
  }
  const seen = new Set();
  const components = comps.map((c, i) => {
    const name = c.name || `component_${i + 1}`;
    if (seen.has(name)) cli.die(`duplicate component name: ${name}`, { prefix: TOOL });
    seen.add(name);
    const base = Number(c.base ?? c.monthly ?? c.base_monthly);
    if (!Number.isFinite(base) || base < 0) {
      cli.die(`component "${name}": base must be a non-negative monthly amount`, { prefix: TOOL });
    }
    const gMu = Number(c.g_mu ?? 0);
    const gSd = Number(c.g_sd ?? 0);
    const sigM = Number(c.sig_m ?? 0);
    for (const [k, v] of [['g_mu', gMu], ['g_sd', gSd], ['sig_m', sigM]]) {
      if (!Number.isFinite(v)) cli.die(`component "${name}": ${k} is not a number`, { prefix: TOOL });
    }
    if (gSd < 0 || sigM < 0) cli.die(`component "${name}": g_sd and sig_m cannot be negative`, { prefix: TOOL });
    // Damping is allowed, but never silently: a free-text why is mandatory.
    let damping = null;
    if (c.damping) {
      if (typeof c.damping !== 'object' || Array.isArray(c.damping)) {
        cli.die(`component "${name}": damping must be an object with a "why"`, { prefix: TOOL });
      }
      const why = typeof c.damping.why === 'string' ? c.damping.why.trim() : '';
      if (!why) {
        cli.die(
          `component "${name}": damping requires a non-empty "why" string.\n` +
            `  Record WHY the measured volatility was overridden, e.g.\n` +
            `  "damping": {"measured_12m": 0.90, "applied": ${gSd}, "why": "raw figure contaminated by the Feb-2026 regime break"}`,
          { prefix: TOOL }
        );
      }
      damping = { measured_12m: c.damping.measured_12m ?? c.damping.measured ?? null, applied: c.damping.applied ?? gSd, why };
    }
    return { name, base, g_mu: gMu, g_sd: gSd, sig_m: sigM, kind: c.kind || (sigM === 0 ? 'fixed' : 'usage'), note: c.note || '', damping };
  });

  const toggles = (raw.toggles || []).map((t, i) => {
    const name = t.name || `toggle_${i + 1}`;
    const prob = Number(t.prob ?? t.probability ?? 0.5);
    if (!(prob >= 0 && prob <= 1)) cli.die(`toggle "${name}": prob must be within 0..1`, { prefix: TOOL });
    // A missing amount must be an ERROR, not a silent zero: a misspelled
    // "anual" would otherwise leave the toggle listed as configured while
    // contributing nothing, understating every percentile. (PR #318 review, P2)
    const hasAnnual = t.annual !== undefined && t.annual !== null;
    const hasMonthly = t.monthly !== undefined && t.monthly !== null;
    if (!hasAnnual && !hasMonthly) {
      cli.die(
        `toggle "${name}": needs "annual" or "monthly" (neither was present — check for a typo)`,
        { prefix: TOOL }
      );
    }
    const annual = hasAnnual ? Number(t.annual) : Number(t.monthly) * 12;
    if (!Number.isFinite(annual)) {
      cli.die(`toggle "${name}": "annual"/"monthly" must be a number`, { prefix: TOOL });
    }
    return { name, annual, prob, why: t.why || t.note || '' };
  });

  const horizon = Math.round(num(raw.horizon_months, 12));
  if (horizon < 1 || horizon > 120) cli.die('horizon_months must be within 1..120', { prefix: TOOL });

  const cfg = {
    name: raw.name || 'projection',
    base_month: raw.base_month || null,
    start: raw.start || null,
    horizon_months: horizon,
    accrue_months: Math.round(num(raw.accrue_months, 0)),
    fiscal_year_start: Math.round(num(flags['fiscal-year-start'], num(raw.fiscal_year_start, 1))),
    components,
    toggles,
    note: raw.note || '',
  };
  if (cfg.fiscal_year_start < 1 || cfg.fiscal_year_start > 12) {
    cli.die('--fiscal-year-start must be a month number 1-12', { prefix: TOOL });
  }
  if (cfg.base_month && monthIndex(cfg.base_month) === null) cli.die(`base_month ${cfg.base_month} is not YYYY-MM`, { prefix: TOOL });
  if (cfg.start && monthIndex(cfg.start) === null) cli.die(`start ${cfg.start} is not YYYY-MM`, { prefix: TOOL });
  return cfg;
}

// First month of the fiscal year that begins on/after base_month.
function fyStartIndex(baseIdx, fyStartMonth) {
  const year = Math.floor(baseIdx / 12);
  let candidate = year * 12 + (fyStartMonth - 1);
  // Strictly-before, so a base month that IS the fiscal start month returns
  // itself rather than jumping a full year forward. (PR #318 review, P2)
  if (candidate < baseIdx) candidate += 12;
  return candidate;
}

// `base` is the monthly level at the FIRST MONTH OF THE PROJECTION WINDOW, so
// growth accrues from month 1 of the window and the accrual offset is 0.
// `base_month` only records when the level was measured (provenance).
// If the measurement predates the window and you want the gap rolled forward,
// set "accrue_months": N explicitly — it is never inferred, because silently
// compounding growth over a measurement gap inflates every percentile.
function windowFor(cfg) {
  const baseIdx = cfg.base_month ? monthIndex(cfg.base_month) : null;
  const startIdx = cfg.start
    ? monthIndex(cfg.start)
    : baseIdx === null
      ? null
      : fyStartIndex(baseIdx, cfg.fiscal_year_start);
  return { startIdx, offset: cfg.accrue_months, baseIdx };
}

// ── the Monte Carlo ───────────────────────────────────────────────────
// THE RULE: one growth draw per component PER SIMULATED YEAR, held across all
// months of that year. Monthly jitter is iid and averages down by sqrt(12).
function simulateCore(cfg, opts = {}) {
  const runs = opts.runs || RUNS;
  const rng = mulberry32(opts.seed === undefined ? SEED : opts.seed);
  const normal = makeNormal(rng);
  const useGrowth = opts.growth !== false;
  const useJitter = opts.jitter !== false;
  const useToggles = opts.toggles !== false;
  const overrides = opts.toggleOverrides || {};
  const offset = opts.offset || 0;
  const perMonthGrowth = (opts.growthDraw || GROWTH_DRAW) === 'per-month';
  const H = cfg.horizon_months;
  const comps = cfg.components;
  const nC = comps.length;

  const totals = new Float64Array(runs);
  const per = comps.map(() => new Float64Array(runs));
  const toggleHits = cfg.toggles.map(() => 0);

  for (let r = 0; r < runs; r++) {
    let total = 0;
    for (let ci = 0; ci < nC; ci++) {
      const c = comps[ci];
      let sub = 0;
      // Log growth accrued from the base level to the START of the current
      // month. Accumulating month by month (rather than re-applying the
      // current year's draw to the whole elapsed period) is what keeps a
      // multi-year path continuous: year one's draw stays baked in and the
      // new draw only governs the months it actually covers.
      // (PR #318 review, P1)
      let accrued = 0;
      let g = 0;
      let yearOfDraw = -1;
      // Walk every month since the base, including the pre-window months an
      // accrue_months / fiscal shift implies, so growth compounds over them.
      const lastMonth = offset + H;
      for (let t = 1; t <= lastMonth; t++) {
        const yr = Math.floor((t - 1) / 12);
        if (perMonthGrowth) {
          // THE BUG, kept switchable for audit: a fresh draw every month.
          g = c.g_mu + (useGrowth && c.g_sd > 0 ? c.g_sd * normal() : 0);
        } else if (yr !== yearOfDraw) {
          // One persistent draw per simulated year.
          g = c.g_mu + (useGrowth && c.g_sd > 0 ? c.g_sd * normal() : 0);
          yearOfDraw = yr;
        }
        if (t > offset) {
          // Mid-month convention: half a month of the current rate on top of
          // everything accrued before it. For a 12-month window at offset 0
          // this is exactly base * exp(g * (m - 0.5) / 12).
          let level = c.base * Math.exp(accrued + (g * 0.5) / 12);
          if (useJitter && c.sig_m > 0) {
            // Median-preserving lognormal jitter.
            level *= Math.exp(c.sig_m * normal() - (c.sig_m * c.sig_m) / 2);
          }
          sub += level;
        }
        accrued += g / 12;
      }
      per[ci][r] = sub;
      total += sub;
    }
    if (useToggles) {
      for (let ti = 0; ti < cfg.toggles.length; ti++) {
        const t = cfg.toggles[ti];
        const p = overrides[t.name] === undefined ? t.prob : overrides[t.name];
        if (p >= 1 || (p > 0 && rng() < p)) {
          total += (t.annual * H) / 12;
          toggleHits[ti]++;
        }
      }
    }
    totals[r] = total;
  }
  return { totals, per, runs, toggleHits };
}

const PCTS = [0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95];

function summarize(totals) {
  const sorted = Float64Array.prototype.slice.call(totals).sort();
  const out = {};
  for (const p of PCTS) out[`p${String(Math.round(p * 100)).padStart(2, '0')}`] = percentile(sorted, p);
  out.mean = mean(sorted);
  out.sd = stdev(Array.from(sorted));
  return out;
}

function bandWidth(s) {
  return s.p50 > 0 ? (s.p90 - s.p10) / s.p50 : 0;
}

// A one-year-ahead cloud-cost band tighter than ~15% is a modelling smell, not
// a precise forecast — most often growth drawn per month instead of per year.
function narrowBandWarning(cfg, s) {
  const width = bandWidth(s);
  const scale = Math.sqrt(cfg.horizon_months / 12);
  const floor = 0.15 * scale;
  if (width >= floor) return null;
  return (
    `p10-p90 width is only ${(width * 100).toFixed(1)}% of the median over ${cfg.horizon_months} months ` +
    `(expected at least ~${(floor * 100).toFixed(0)}%). That is implausibly precise for a cloud-cost forecast. Check that:\n` +
    `      · growth uncertainty is drawn once per YEAR, not per month (this tool does; a hand-rolled model often does not)\n` +
    `      · every g_sd matches the volatility you measured with '${TOOL} fit' — under-set g_sd is the usual cause\n` +
    `      · you did not damp g_sd below the measured 12-month uncertainty without a recorded reason`
  );
}

function printComponentTable(cfg, per, totalP50) {
  const rows = cfg.components.map((c, i) => {
    const sorted = Float64Array.prototype.slice.call(per[i]).sort();
    return {
      name: c.name,
      kind: c.kind,
      p10: percentile(sorted, 0.1),
      p50: percentile(sorted, 0.5),
      p90: percentile(sorted, 0.9),
      damped: !!c.damping,
    };
  });
  rows.sort((a, b) => b.p50 - a.p50);
  const w = Math.max(...rows.map((r) => r.name.length), 9);
  console.log(`  ${'component'.padEnd(w)}  ${'kind'.padEnd(6)}  ${'p10'.padStart(10)}  ${'p50'.padStart(10)}  ${'p90'.padStart(10)}  share`);
  for (const r of rows) {
    const share = totalP50 > 0 ? `${((r.p50 / totalP50) * 100).toFixed(1)}%` : 'n/a';
    console.log(
      `  ${r.name.padEnd(w)}  ${r.kind.padEnd(6)}  ${usd(r.p10).padStart(10)}  ${color.bold(usd(r.p50).padStart(10))}  ${usd(r.p90).padStart(10)}  ${share.padStart(6)}${r.damped ? color.dim(' (damped)') : ''}`
    );
  }
  return rows;
}

async function cmdSimulate(positional) {
  const raw = await loadJson(positional[0], 'config.json');
  const cfg = normalizeConfig(raw, positional[0]);
  const win = windowFor(cfg);
  const res = simulateCore(cfg, { offset: win.offset });
  const s = summarize(res.totals);
  const warn = narrowBandWarning(cfg, s);

  const compRows = cfg.components.map((c, i) => {
    const sorted = Float64Array.prototype.slice.call(res.per[i]).sort();
    return { name: c.name, kind: c.kind, p10: percentile(sorted, 0.1), p50: percentile(sorted, 0.5), p90: percentile(sorted, 0.9) };
  });

  // Fiscal vs calendar year: compute the difference, never assume it is zero.
  let fyVsCy = null;
  if (cfg.fiscal_year_start !== 1 && win.startIdx !== null) {
    // The calendar year covering the same period starts in the next January;
    // that window sits N months further along the same growth curve.
    const cyStartIdx = fyStartIndex(win.startIdx, 1);
    const shift = cyStartIdx - win.startIdx;
    const cyRes = simulateCore(cfg, { offset: win.offset + shift, seed: SEED }); // common random numbers
    const cyS = summarize(cyRes.totals);
    fyVsCy = {
      fiscal: { start: monthLabel(win.startIdx), p50: s.p50 },
      calendar: { start: monthLabel(cyStartIdx), p50: cyS.p50 },
      shift_months: shift,
      delta_abs: s.p50 - cyS.p50,
      delta_pct: cyS.p50 > 0 ? (s.p50 - cyS.p50) / cyS.p50 : null,
    };
  }

  if (JSON_OUT) {
    cli.out({
      command: 'simulate',
      config: cfg.name,
      runs: res.runs,
      seed: SEED,
      growth_draw: GROWTH_DRAW,
      horizon_months: cfg.horizon_months,
      window: win.startIdx === null ? null : { start: monthLabel(win.startIdx), end: monthLabel(win.startIdx + cfg.horizon_months - 1), offset_months: win.offset },
      total: s,
      band_width_pct_of_median: bandWidth(s),
      components: compRows,
      toggles: cfg.toggles.map((t, i) => ({ name: t.name, prob: t.prob, annual: t.annual, realised_rate: res.toggleHits[i] / res.runs })),
      fiscal_vs_calendar: fyVsCy,
      warning: warn,
    });
    if (warn) console.warn(`${TOOL}: ${warn}`);
    return;
  }

  console.log('');
  console.log(`  ${color.cyan(color.bold(cfg.name))}`);
  const windowTxt = win.startIdx === null
    ? `${cfg.horizon_months} months from the base level`
    : `${monthLabel(win.startIdx)} → ${monthLabel(win.startIdx + cfg.horizon_months - 1)}`;
  console.log(color.dim(`  window ${windowTxt} · base measured ${cfg.base_month || 'n/a'} · offset ${win.offset}m · ${res.runs.toLocaleString('en-US')} runs · seed ${SEED}`));
  if (GROWTH_DRAW === 'per-month') {
    console.log(
      `  ${color.red('AUDIT MODE')} growth redrawn every month — the band below is understated by roughly sqrt(12) and must not be quoted.`
    );
  }
  console.log(RULE);
  console.log(`  ${color.bold('Annual total')}`);
  for (const p of PCTS) {
    const key = `p${String(Math.round(p * 100)).padStart(2, '0')}`;
    const label = key.toUpperCase();
    const bar = key === 'p50' ? color.bold(usd(s[key])) : usd(s[key]);
    console.log(`    ${label}  ${bar.padStart(key === 'p50' ? 20 : 11)}`);
  }
  console.log(color.dim(`    mean ${usd(s.mean)} · sd ${usd(s.sd)}`));
  console.log('');
  console.log(`  ${color.bold('P10-P90 width')}  ${color.bold(`${(bandWidth(s) * 100).toFixed(1)}%`)} of median ${color.dim(`(±${((bandWidth(s) / 2) * 100).toFixed(1)}%)`)}`);
  console.log('');
  console.log(`  ${color.bold('By component')} ${color.dim('(annual $, sorted by p50)')}`);
  printComponentTable(cfg, res.per, s.p50);
  if (cfg.toggles.length) {
    console.log('');
    console.log(`  ${color.bold('Scenario toggles')}`);
    for (let i = 0; i < cfg.toggles.length; i++) {
      const t = cfg.toggles[i];
      console.log(`    ${t.name}  ${usd((t.annual * cfg.horizon_months) / 12)}/period at p=${t.prob}  ${color.dim(`realised ${((res.toggleHits[i] / res.runs) * 100).toFixed(1)}%`)}`);
      if (t.why) console.log(color.dim(`      ${t.why}`));
    }
  }
  if (fyVsCy) {
    console.log('');
    console.log(`  ${color.bold('Fiscal vs calendar year')} ${color.dim('(common random numbers)')}`);
    console.log(`    fiscal   ${fyVsCy.fiscal.start} start   P50 ${usd(fyVsCy.fiscal.p50)}`);
    console.log(`    calendar ${fyVsCy.calendar.start} start   P50 ${usd(fyVsCy.calendar.p50)}`);
    console.log(`    delta    ${usd(fyVsCy.delta_abs)}  ${pctStr(fyVsCy.delta_pct)}  ${color.dim(fyVsCy.delta_pct !== null && Math.abs(fyVsCy.delta_pct) < bandWidth(s) / 2 ? 'well inside the band' : 'material — quote the fiscal window explicitly')}`);
  }
  if (warn) {
    console.log('');
    console.log(`  ${color.yellow('warn')} ${warn}`);
  }
  console.log('');
  console.log(color.dim('  An empirical percentile band, not a confidence interval — see references/methodology.md.'));
}

// ── variance ──────────────────────────────────────────────────────────
async function cmdVariance(positional) {
  const raw = await loadJson(positional[0], 'config.json');
  const cfg = normalizeConfig(raw, positional[0]);
  const win = windowFor(cfg);
  const opts = { offset: win.offset };
  const all = summarize(simulateCore(cfg, opts).totals);
  const growthOnly = summarize(simulateCore(cfg, { ...opts, jitter: false, toggles: false }).totals);
  const jitterOnly = summarize(simulateCore(cfg, { ...opts, growth: false, toggles: false }).totals);
  const toggleOnly = cfg.toggles.length
    ? summarize(simulateCore(cfg, { ...opts, growth: false, jitter: false }).totals)
    : null;

  const parts = [
    { source: 'growth (persistent, per year)', sd: growthOnly.sd },
    { source: 'jitter (iid, per month)', sd: jitterOnly.sd },
  ];
  if (toggleOnly) parts.push({ source: 'scenario toggles (Bernoulli)', sd: toggleOnly.sd });
  const totalSd = all.sd;
  const quad = Math.sqrt(parts.reduce((a, p) => a + p.sd * p.sd, 0));
  for (const p of parts) {
    p.sd_share = totalSd > 0 ? p.sd / totalSd : 0;
    p.var_share = totalSd > 0 ? (p.sd * p.sd) / (totalSd * totalSd) : 0;
  }

  if (JSON_OUT) {
    cli.out({
      command: 'variance',
      config: cfg.name,
      runs: RUNS,
      seed: SEED,
      total_sd: totalSd,
      total_p50: all.p50,
      quadrature_sum: quad,
      components: parts,
    });
    return;
  }
  console.log('');
  console.log(`  ${color.cyan(color.bold(cfg.name))} ${color.dim('— variance decomposition')}`);
  console.log(color.dim(`  ${RUNS.toLocaleString('en-US')} runs · seed ${SEED} · each source isolated by zeroing the others`));
  console.log(RULE);
  console.log(`  total sd of the annual total   ${color.bold(usd(totalSd))}  ${color.dim(`(${((totalSd / all.p50) * 100).toFixed(1)}% of P50)`)}`);
  console.log('');
  console.log(`  ${'source'.padEnd(32)}  ${'sd'.padStart(10)}  ${'sd share'.padStart(9)}  ${'var share'.padStart(9)}`);
  for (const p of parts) {
    console.log(`  ${p.source.padEnd(32)}  ${usd(p.sd).padStart(10)}  ${`${(p.sd_share * 100).toFixed(1)}%`.padStart(9)}  ${`${(p.var_share * 100).toFixed(1)}%`.padStart(9)}`);
  }
  console.log(color.dim(`  quadrature sum ${usd(quad)} vs measured ${usd(totalSd)} — close agreement means the sources are near-independent.`));
  console.log('');
  const g = parts[0];
  const j = parts[1];
  console.log(`  ${color.bold('Reading:')} persistent growth uncertainty carries ${color.bold(`${(g.var_share * 100).toFixed(0)}%`)} of the variance;`);
  console.log(`  iid monthly jitter carries ${color.bold(`${(j.var_share * 100).toFixed(0)}%`)} despite a sig_m that looks large per month.`);
  console.log(color.dim('  Jitter averages down by sqrt(12) across the year; a persistent growth error does not.'));
  console.log(color.dim('  So: spend your effort on the growth range, not on monthly noise.'));
}

// ── scenario ──────────────────────────────────────────────────────────
async function cmdScenario(positional, flags) {
  const raw = await loadJson(positional[0], 'config.json');
  const cfg = normalizeConfig(raw, positional[0]);
  const win = windowFor(cfg);
  let toggleArgs = flags.toggle === undefined ? [] : flags.toggle;
  if (!Array.isArray(toggleArgs)) toggleArgs = [toggleArgs];
  const overrides = {};
  for (const spec of toggleArgs) {
    const m = /^([^=]+)=(.+)$/.exec(String(spec));
    if (!m) cli.die(`--toggle wants <name>=<prob>, got ${JSON.stringify(spec)}`, { prefix: TOOL });
    const name = m[1].trim();
    const p = Number(m[2]);
    if (!(p >= 0 && p <= 1)) cli.die(`--toggle ${name}: probability must be within 0..1`, { prefix: TOOL });
    if (!cfg.toggles.some((t) => t.name === name)) {
      const known = cfg.toggles.map((t) => t.name).join(', ') || '(none defined)';
      cli.die(`unknown toggle "${name}" — config defines: ${known}`, { prefix: TOOL });
    }
    overrides[name] = p;
  }
  if (!cfg.toggles.length) {
    cli.die(
      `config has no "toggles" — add uncertain line items, e.g.\n` +
        `  "toggles": [{"name": "marketplace", "annual": 12000, "prob": 0.5, "why": "contract may be cancelled"}]`,
      { prefix: TOOL }
    );
  }

  const blended = summarize(simulateCore(cfg, { offset: win.offset, toggleOverrides: overrides }).totals);
  const rows = [];
  for (const t of cfg.toggles) {
    const p = overrides[t.name] === undefined ? t.prob : overrides[t.name];
    const off = summarize(simulateCore(cfg, { offset: win.offset, toggleOverrides: { ...overrides, [t.name]: 0 } }).totals);
    const on = summarize(simulateCore(cfg, { offset: win.offset, toggleOverrides: { ...overrides, [t.name]: 1 } }).totals);
    rows.push({
      name: t.name,
      prob: p,
      annual: (t.annual * cfg.horizon_months) / 12,
      why: t.why,
      p50_excluded: off.p50,
      p50_included: on.p50,
      swing: on.p50 - off.p50,
      expected_contribution: ((t.annual * cfg.horizon_months) / 12) * p,
    });
  }

  if (JSON_OUT) {
    cli.out({ command: 'scenario', config: cfg.name, runs: RUNS, seed: SEED, blended, toggles: rows });
    return;
  }
  console.log('');
  console.log(`  ${color.cyan(color.bold(cfg.name))} ${color.dim('— scenario toggles')}`);
  console.log(color.dim(`  ${RUNS.toLocaleString('en-US')} runs · seed ${SEED}`));
  console.log(RULE);
  console.log(`  ${color.bold('Blended total')} ${color.dim('(each toggle included with its probability)')}`);
  console.log(`    P10 ${usd(blended.p10)}   P50 ${color.bold(usd(blended.p50))}   P90 ${usd(blended.p90)}   ${color.dim(`width ${(bandWidth(blended) * 100).toFixed(1)}%`)}`);
  console.log('');
  for (const r of rows) {
    console.log(`  ${color.bold(r.name)}  ${color.dim(`${usd(r.annual)}/period · p=${r.prob}`)}`);
    if (r.why) console.log(color.dim(`    ${r.why}`));
    console.log(`    excluded (p=0)  P50 ${usd(r.p50_excluded)}`);
    console.log(`    included (p=1)  P50 ${usd(r.p50_included)}`);
    console.log(`    swing           ${usd(r.swing)}   ${color.dim(`expected contribution ${usd(r.expected_contribution)}`)}`);
    console.log('');
  }
  console.log(color.dim('  A blended P50 hides a bimodal outcome: quote both branches when a toggle is large.'));
}

// ── example ───────────────────────────────────────────────────────────
const EXAMPLE_CONFIG = {
  name: 'Example platform — FY27 cloud cost',
  base_month: '2026-08',
  fiscal_year_start: 12,
  horizon_months: 12,
  note: 'Illustrative figures. A Dec-Nov fiscal year: FY27 = Dec 2026 - Nov 2027. Bases are post-break monthly means.',
  components: [
    { name: 'cdn_fixed', base: 2500, g_mu: 0.0, g_sd: 0.08, sig_m: 0.0, kind: 'fixed', note: 'contractual support + TLS bundle, flat 8 months' },
    { name: 'cdn_usage', base: 4000, g_mu: 0.24, g_sd: 0.14, sig_m: 0.035, kind: 'usage', note: 'cleanest series, CV 3.1%, undamped' },
    { name: 'compute_authoring', base: 9000, g_mu: 0.05, g_sd: 0.35, sig_m: 0.12, kind: 'usage', note: 'post-optimisation regime; post-break mean verified over 6 months', damping: { measured_12m: 0.88, applied: 0.35, why: 'raw 88% is contaminated by the optimisation break and a partial month; damped to the post-break dispersion' } },
    { name: 'compute_delivery', base: 500, g_mu: 0.12, g_sd: 0.3, sig_m: 0.14, kind: 'usage', note: 'small, stable' },
    { name: 'analytics_db', base: 1000, g_mu: 0.0, g_sd: 0.4, sig_m: 0.15, kind: 'usage', note: 'post-wind-down; only 2 clean months, thin evidence' },
    { name: 'platform_prod', base: 3000, g_mu: 0.15, g_sd: 0.35, sig_m: 0.1, kind: 'usage', note: 'production subset only', damping: { measured_12m: 0.8, applied: 0.35, why: 'raw 80% spans a project-scope change; damped to the production-subset dispersion' } },
    { name: 'edge_contract', base: 6000, g_mu: 0.05, g_sd: 0.15, sig_m: 0.0, kind: 'fixed', note: 'step function, CV 0%, g_mu is renewal uplift' },
    { name: 'edge_variable', base: 10000, g_mu: 0.2, g_sd: 0.45, sig_m: 0.18, kind: 'usage', note: 'usage overage; recent clean-window mean ~$10,400, CV 19%', damping: { measured_12m: 3.9, applied: 0.45, why: 'raw 390% is an artefact of a partial month ($600 vs a ~$10,000 typical month); damped to the clean-window CV of 19% scaled to a year' } },
  ],
  toggles: [{ name: 'marketplace', annual: 12000, prob: 0.5, why: 'marketplace listing may be cancelled before the period starts' }],
};

const EXAMPLE_SERIES = {
  series: [
    {
      name: 'edge_variable',
      note: 'usage overage — shows the partial-month trap',
      points: [
        { month: '2026-01', amount: 9200 },
        { month: '2026-02', amount: 9800 },
        { month: '2026-03', amount: 10300 },
        { month: '2026-04', amount: 10600 },
        { month: '2026-05', amount: 10100 },
        { month: '2026-06', amount: 11000 },
        { month: '2026-07', amount: 10750 },
        { month: '2026-08', amount: 600, partial: true, note: 'incomplete billing period, not a decline' },
      ],
    },
    {
      name: 'compute_authoring',
      note: 'shows a deliberate optimisation break in Feb 2026',
      points: [
        { month: '2025-09', amount: 11000 },
        { month: '2025-10', amount: 12500 },
        { month: '2025-11', amount: 13800 },
        { month: '2025-12', amount: 15000 },
        { month: '2026-01', amount: 14500 },
        { month: '2026-02', amount: 8600 },
        { month: '2026-03', amount: 8450 },
        { month: '2026-04', amount: 8700 },
        { month: '2026-05', amount: 8800 },
        { month: '2026-06', amount: 8550 },
        { month: '2026-07', amount: 8750 },
      ],
    },
    {
      name: 'cdn_fixed',
      note: 'contractual line — CV is exactly 0',
      points: [
        { month: '2026-01', amount: 2500 },
        { month: '2026-02', amount: 2500 },
        { month: '2026-03', amount: 2500 },
        { month: '2026-04', amount: 2500 },
        { month: '2026-05', amount: 2500 },
        { month: '2026-06', amount: 2500 },
        { month: '2026-07', amount: 2500 },
      ],
    },
  ],
};

function cmdExample(positional) {
  const which = (positional[0] || 'config').toLowerCase();
  if (which === 'config') cli.out(EXAMPLE_CONFIG);
  else if (which === 'series') cli.out(EXAMPLE_SERIES);
  else cli.die(`example wants 'config' or 'series', got ${JSON.stringify(which)}`, { prefix: TOOL });
}

// ── main ──────────────────────────────────────────────────────────────
async function main() {
  if (flags.help === true || flags.h === true || !subcommand || subcommand === 'help') cli.help(HELP);
  try {
    if (subcommand === 'fit') await cmdFit(positional, flags);
    else if (subcommand === 'detect-breaks') await cmdDetectBreaks(positional, flags);
    else if (subcommand === 'simulate') await cmdSimulate(positional);
    else if (subcommand === 'variance') await cmdVariance(positional);
    else if (subcommand === 'scenario') await cmdScenario(positional, flags);
    else if (subcommand === 'example') cmdExample(positional);
    else cli.die(`unknown command: ${subcommand}\nRun '${TOOL} --help' for usage.`, { prefix: TOOL });
  } catch (err) {
    if (err?.name === 'NodeExitError') throw err; // mandatory: cli.die unwinds this way
    cli.die(err.message, { prefix: TOOL });
  }
}

await main();
