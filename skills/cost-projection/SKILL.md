---
name: cost-projection
description: Turn measured historical monthly cost series into a probabilistic forward projection by Monte Carlo — fit growth and volatility per component, detect regime breaks that invalidate naive growth fits, simulate p05-p95 percentile bands for an annual total, decompose the variance into persistent growth uncertainty vs iid monthly jitter, and toggle uncertain line items with Bernoulli scenarios. Use when the user asks to forecast, project or budget cloud spend for a coming year, wants a P50/P90 band or a confidence range on a cost total, asks "what will X cost next year", needs an FY vs calendar-year cost number, or has monthly AWS/GCP/Azure/Fastly/Cloudflare bills and wants a defensible forward number with an honest uncertainty band. Not a billing API client — it consumes cost series from skills like fastly-ext, gcloud or cloudflare and never calls a vendor API. Not a budgeting, invoicing, expense-report or purchase-approval tool (that is concur), and not a generic timeseries or revenue forecaster.
allowed-tools: bash
command: cost-projection
script: scripts/cost-projection.jsh
---

# cost-projection

Monte Carlo forward projection of cloud cost from measured monthly series. Pure
computation — reads JSON from disk, writes percentiles. No network, no
credentials. Get the series from a billing skill (`fastly-ext billing`, `gcloud`,
`cloudflare`) or a CSV export, then project them here.

**The one rule that matters:** growth uncertainty is drawn **once per simulated
year** and held across all 12 months. Monthly jitter is iid, averages down by
sqrt(12), and barely widens an annual total. Drawing growth per month instead
collapses the band to a falsely precise width — measured on the worked example,
8.9% instead of the honest 22.2%. This tool draws per year by default; you can
reproduce the failure with `--growth-draw per-month`.

## Quick start

```bash
cost-projection example series > series.json   # a runnable sample
cost-projection example config > config.json

cost-projection detect-breaks series.json      # ALWAYS do this first
cost-projection fit series.json --explain      # growth, CV, volatility, damping rationale
cost-projection fit series.json --from 2026-02 # refit on the post-break window

cost-projection simulate config.json           # p05-p95 + per-component p10/p50/p90
cost-projection variance config.json           # growth vs jitter vs toggles
cost-projection scenario config.json --toggle marketplace=0.5
```

Put the file argument **before** valueless flags (`fit s.json --json`): the
parser reads the token after `--json` as that flag's value.

## Workflow

1. **`detect-breaks` first.** A regime break makes every growth fit meaningless.
   A series that ran $80/mo then jumped to $5,200 reports a full-history growth
   of thousands of percent; a series that ramped to $15,000/mo then was
   deliberately halved reports −24%/yr on full history and +4%/yr on the
   post-break window. Both are artefacts. `detect-breaks` scans every split point, reports
   the segment-mean ratio with a Welch t, and suggests a `--from` window.
2. **`fit` the post-break window.** Reports n months, mean level, CV,
   month-over-month log mean and log sd, annualised growth over the full window
   *and* the trailing 6 months, and the implied 12-month-ahead level uncertainty
   (`mom_sd × sqrt(12)`). It emits a ready-to-paste component stanza — and
   **withholds** it when the window still looks contaminated.
3. **Split fixed from usage.** A contractual subscription does not grow with
   traffic: a contractual line measures a CV of exactly 0.0%. Model it as its
   own component with `sig_m: 0` and a `g_sd` that represents renewal uplift
   only. Usage lines get both.
4. **`simulate`.** Per component: `base` (monthly $ at the first month of the
   projection window), `g_mu` (expected annual log-growth), `g_sd` (uncertainty
   *on that growth*), `sig_m` (month-to-month lognormal jitter). 200,000 runs by
   default; `--runs N`, `--seed N` for a reproducible band.
5. **Sanity-check the width.** `simulate` warns when the p10–p90 width is under
   ~15% of the median for a 12-month horizon. A tighter band on a one-year-ahead
   cloud forecast is a modelling bug, almost always an under-set `g_sd`.
6. **`variance`** to see where the spread comes from, and **`scenario`** for line
   items that may not survive into the period at all.

## Traps this tool actively prevents

- **Partial billing months.** A current-month figure of $600 against a ~$10,000
  typical month is an incomplete period, not a decline. Mark the point
  `"partial": true` (or `"estimated": true`) and fits exclude it. Left in, that
  one point produced a bogus ±382% volatility and −99.1%/yr growth on the
  shipped sample series.
  `detect-breaks` also flags *unflagged* suspects — a last point far below the
  trailing median — and excludes flagged points from the break scan so they
  cannot invent a phantom step down.
- **Silent damping.** Contaminated volatility often must be damped (measured
  88% → applied 35%). A `damping` block therefore **requires** a free-text
  `why`; `simulate` exits non-zero without one, and `fit --explain` prints every
  rationale. Never damp without recording the reason.
- **Fiscal vs calendar year.** A Dec–Nov fiscal year is not Jan–Dec.
  `--fiscal-year-start 12` computes the difference against the calendar year
  with common random numbers rather than assuming it is zero — on the worked
  example it is −1.3%, inside the band but not zero.

## Reading the output

The band is an **empirical percentile band** over simulated futures, not a
confidence interval on a fitted parameter. P90 means "90% of simulated years
came in below this, given these growth assumptions" — it is only as honest as
the widest `g_sd` you were willing to write down.

## References

- `references/COMMANDS.md` — every command, flag, and the JSON schemas for
  series and config files.
- `references/methodology.md` — why growth is drawn per year, the variance
  decomposition, regime-break detection, damping discipline, and empirical
  bands vs confidence intervals.
- `references/example.md` — a full worked projection end to end, with the
  inputs, the damping rationales and the verified output of every command.
