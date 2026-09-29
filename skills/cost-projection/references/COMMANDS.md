# cost-projection — commands and file formats

Every command accepts `--json` and then prints the raw result object to stdout
and nothing else, so it can be piped into `node`/`jq`. Advisory warnings go to
stderr in `--json` mode and are inlined in the report in human mode. Every
failure exits non-zero, including in `--json` mode.

Global flags: `--json`, `--runs N`, `--seed N`, `--growth-draw <per-year|per-month>`.

**Argument order matters.** `process.argv.parseFlags()` gives a valueless flag
the following token as its value, so `simulate --json cfg.json` would read the
path as the value of `--json`. Put the file first: `simulate cfg.json --json`.
As a safety net the tool reclaims a `*.json` value found on `--json`,
`--explain` or `--include-partial` and treats it as the positional path.

---

## `fit <series.json>`

Measures growth and volatility per series.

| Flag | Meaning |
|---|---|
| `--from YYYY-MM` | Fit only points from this month onward (the post-break window) |
| `--explain` | Print the recorded `damping` rationale and note for each series |
| `--include-partial` | Include points marked `partial`/`estimated` (unsafe; off by default) |

Reported per series:

- `n_used` / `n_excluded`, and every excluded point with its reason.
- `mean_level`, `sd_level`, `cv` (= sd/mean on levels), `min`, `max`, `last`.
- `mom_log_mean`, `mom_log_sd` — month-over-month log returns, computed from
  **adjacent months only**. A gap is skipped and counted in `n_gaps_skipped`;
  treating a gap as one step would inflate drift and understate volatility.
- `growth_full` — annualised endpoint growth over the whole window,
  `(last/first)^(12/span_months) − 1`.
- `growth_6m` — the same over the trailing 6 months. Compare the two: a sign
  disagreement is the signature of a regime break.
- `growth_drift` — `exp(12 × mom_log_mean) − 1`, the drift the simulator uses.
- `implied_12m_uncertainty` — `mom_log_sd × sqrt(12)`, the 12-month-ahead level
  uncertainty implied by the monthly volatility. This is the natural starting
  point for `g_sd`.
- `suggested_component` — a paste-ready stanza for a config. It is **withheld**
  (`suggestion_safe: false`) when the window looks break-contaminated:
  `|growth_full| > 5`, implied uncertainty > 100%, or a full-vs-6m sign flip.

Warnings fire for implausible full-window growth, full-vs-6m sign
disagreement, a CV of exactly 0 (a fixed contractual line), skipped gaps, and
an implied uncertainty above 100%.

## `detect-breaks <series.json>`

Scans for structural regime breaks. Run this **before** any fit.

| Flag | Meaning |
|---|---|
| `--threshold R` | Segment-mean ratio to flag, default `1.5` (so 1.5x up or ÷1.5 down) |
| `--top N` | How many candidate breaks to list, default `3` |

Method: for every split point with at least 3 points on each side, compare the
means of the two segments and compute a Welch t on the log levels. A split is
reported as a break when the ratio crosses `--threshold`, **or** when it is
statistically crisp (`|t| > 4`) and at least 1.25x — which catches a modest but
unambiguous step such as a contract change.

Points marked `partial`/`estimated` are excluded from the break scan (an
included partial month invents a phantom step down at the tail) but are still
listed under `data_quality`. Separately, `unflagged_suspects` reports any point
below 35% of its trailing 6-month median that is *not* flagged — the usual sign
of a partial month nobody marked.

When a break is found, `recommendation` gives the `--from` month, the post-break
growth, mean and month count, and the exact refit command to run.

## `simulate <config.json>`

The Monte Carlo. Default 200,000 runs.

| Flag | Meaning |
|---|---|
| `--runs N` | Paths, clamped to 1,000–5,000,000. Parsed with `Number`, so `2e5` works |
| `--seed N` | PRNG seed, default `20260827`. Same seed → identical band |
| `--fiscal-year-start M` | First calendar month of the fiscal year, 1–12. A Dec–Nov FY: `12` |
| `--growth-draw MODE` | `per-year` (default) or `per-month` (audit only, see methodology) |

Per run, per component: one growth draw `g ~ Normal(g_mu, g_sd)` held for the
whole simulated year, then for each month `m` of the horizon

```
level_m = base × exp(g × (m − 0.5)/12) × exp(sig_m × Z − sig_m²/2)
```

The mid-month exponent puts month `m` half a month into its own growth, so a
12-month total averages exactly half a year of growth. The `− sig_m²/2` term
keeps the jitter median-preserving. Horizons longer than 12 months redraw `g` for
each new simulated year, and the completed years' growth stays baked in — the
exponent is the running sum of monthly increments, so a path never jumps at a
year boundary. `accrue_months` compounds over the pre-window months the same way.

Output: `p05 p10 p25 p50 p75 p90 p95`, mean and sd of the annual total;
`band_width_pct_of_median` = `(p90 − p10)/p50`; per-component p10/p50/p90 with
its share of the median; realised toggle rates; and the fiscal-vs-calendar
comparison when `fiscal_year_start ≠ 1`.

A warning fires when the p10–p90 width is below `0.15 × sqrt(horizon/12)` of the
median — implausibly precise for a cloud-cost forecast.

## `variance <config.json>`

Isolates each source of spread by re-running with the others zeroed: persistent
per-year growth, iid monthly jitter, and Bernoulli toggles. Reports each one's
sd, its share of the total sd, and its share of the **variance**, plus the
quadrature sum as an independence check.

## `scenario <config.json> --toggle <name>=<prob>`

Bernoulli inclusion for line items whose survival into the period is uncertain.
Repeatable. `<prob>` must be within 0..1; `0` and `1` force the branch. An
unknown toggle name is an error that lists the defined ones.

For each toggle it reports the blended percentiles plus the conditional P50 with
the item excluded and included, the swing between them, and the expected
contribution. Quote both branches when the swing is large — a blended P50 hides
a bimodal outcome.

## `example [config|series]`

Prints the worked example config, or a sample 3-series file demonstrating
the partial-month trap, an optimisation break and a zero-CV contract line.

---

## `series.json`

```json
{
  "series": [
    {
      "name": "edge_variable",
      "note": "usage overage",
      "damping": { "measured_12m": 3.9, "applied": 0.45, "why": "raw figure is a partial-month artefact" },
      "points": [
        { "month": "2026-07", "amount": 10750 },
        { "month": "2026-08", "amount": 600, "partial": true, "note": "incomplete billing period" }
      ]
    }
  ]
}
```

Also accepted: a bare array of series; a single `{name, points}` object; a
`{"name": points}` map; `points` as a `{"YYYY-MM": amount}` map; and `cost` or
`value` in place of `amount`. Points are sorted by month automatically.

Point flags: `partial` (incomplete billing period), `estimated`, `note`.
Non-positive amounts are excluded from fits — a log is undefined there.

## `config.json`

```json
{
  "name": "Example platform — FY27 cloud cost",
  "base_month": "2026-08",
  "fiscal_year_start": 12,
  "horizon_months": 12,
  "accrue_months": 0,
  "components": [
    { "name": "edge_contract", "base": 6000, "g_mu": 0.05, "g_sd": 0.15, "sig_m": 0.0,
      "kind": "fixed", "note": "step function, CV 0%, g_mu is renewal uplift" },
    { "name": "edge_variable", "base": 10000, "g_mu": 0.20, "g_sd": 0.45, "sig_m": 0.18,
      "damping": { "measured_12m": 3.9, "applied": 0.45, "why": "raw 390% is a partial-month artefact; damped to the clean-window CV" } }
  ],
  "toggles": [
    { "name": "marketplace", "annual": 12000, "prob": 0.5, "why": "listing may be cancelled" }
  ]
}
```

| Field | Meaning |
|---|---|
| `base` | Monthly $ at the **first month of the projection window** |
| `g_mu` | Expected annual **log**-growth. `0.24` ≈ +27% simple; `ln(1.24)=0.215` for exactly +24% |
| `g_sd` | Uncertainty on the annual growth. Start from `fit`'s `implied_12m_uncertainty` |
| `sig_m` | Month-to-month lognormal log-sd. `0` for a fixed contractual line |
| `kind` | `fixed` or `usage`, cosmetic; defaults from `sig_m === 0` |
| `damping` | Optional, but `why` is **mandatory** when present |
| `base_month` | Provenance only — when the base level was measured |
| `accrue_months` | Months of growth to accrue *before* month 1, default `0`. Never inferred |
| `horizon_months` | Default 12, max 120 |
| `fiscal_year_start` | 1–12; overridden by `--fiscal-year-start` |
| `toggles[].annual` | Annual $; scaled to the horizon. Or give `monthly` |

`base_month` does **not** roll the base level forward. If your measurement
predates the window and you want the gap compounded, set `accrue_months`
explicitly — it is never inferred, because silently compounding growth across a
measurement gap inflates every percentile.
