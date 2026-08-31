# Worked example

An end-to-end projection on the config the CLI ships. Illustrative figures — a
platform with eight cost components across CDN, compute, analytics and edge.
Everything below is reproducible:

```bash
cost-projection example config > config.json
cost-projection simulate config.json
cost-projection variance config.json
cost-projection scenario config.json --toggle marketplace=0.5
```

## Inputs

Eight components, split fixed vs usage, each with a recorded provenance note.
`g_mu` is an annual **log**-growth, so `0.24` is about +27% simple.

| component | kind | base $/mo | `g_mu` | `g_sd` | `sig_m` | provenance |
|---|---|---|---|---|---|---|
| `cdn_fixed` | fixed | 2,500 | 0.00 | 0.08 | 0.00 | contractual support + TLS bundle, flat 8 months |
| `cdn_usage` | usage | 4,000 | 0.24 | 0.14 | 0.035 | cleanest series, CV 3.1%, undamped |
| `compute_authoring` | usage | 9,000 | 0.05 | 0.35 | 0.12 | post-optimisation regime; post-break mean over 6 months |
| `compute_delivery` | usage | 500 | 0.12 | 0.30 | 0.14 | small, stable |
| `analytics_db` | usage | 1,000 | 0.00 | 0.40 | 0.15 | post-wind-down; only 2 clean months, thin evidence |
| `platform_prod` | usage | 3,000 | 0.15 | 0.35 | 0.10 | production subset only |
| `edge_contract` | fixed | 6,000 | 0.05 | 0.15 | 0.00 | step function, CV 0%, `g_mu` is renewal uplift |
| `edge_variable` | usage | 10,000 | 0.20 | 0.45 | 0.18 | usage overage; clean-window mean ~$10,400, CV 19% |

Plus one Bernoulli toggle: a **$12,000/yr marketplace line at p = 0.5**, because
the listing may be cancelled before the period starts.

Three components carry a `damping` block with a mandatory `why`:

| component | raw implied 12m | applied `g_sd` | recorded `why` |
|---|---|---|---|
| `compute_authoring` | 88% | 0.35 | contaminated by the optimisation break and a partial month; damped to post-break dispersion |
| `platform_prod` | 80% | 0.35 | spans a project-scope change; damped to the production-subset dispersion |
| `edge_variable` | 390% | 0.45 | artefact of a partial month ($600 vs a ~$10,000 typical month); damped to the clean-window CV of 19% scaled to a year |

`cdn_usage` was left **undamped** at 0.14 against a measured 12% — the series
was clean (CV 3.1%), so there was nothing to correct.

## Result

`cost-projection simulate config.json` — 200,000 runs, seed 20260827, window
2026-12 → 2027-11:

| percentile | annual total |
|---|---|
| P05 | $412,696 |
| P10 | **$424,083** |
| P25 | $444,392 |
| P50 | **$469,795** |
| P75 | $498,623 |
| P90 | **$528,400** |
| P95 | $548,396 |

mean $473,790 · sd $41,881 · **p10–p90 width 22.2% of the median (±11.1%)**

### By component

| component | kind | p10 | p50 | p90 | share of P50 |
|---|---|---|---|---|---|
| `edge_variable` | usage | $99,065 | **$132,643** | $182,079 | 28.2% |
| `compute_authoring` | usage | $88,703 | $110,649 | $140,630 | 23.6% |
| `edge_contract` | fixed | $67,120 | $73,839 | $81,472 | 15.7% |
| `cdn_usage` | usage | $49,435 | $54,254 | $59,683 | 11.5% |
| `platform_prod` | usage | $31,030 | $38,839 | $49,409 | 8.3% |
| `cdn_fixed` | fixed | $28,515 | $29,999 | $31,589 | 6.4% |
| `analytics_db` | usage | $9,322 | $11,991 | $15,754 | 2.6% |
| `compute_delivery` | usage | $5,228 | $6,367 | $7,855 | 1.4% |

`edge_variable` is the largest single line at a p50 of about $133k and also the
widest — it alone spans $83k between p10 and p90. Any effort spent narrowing
this projection belongs there.

### Fiscal vs calendar year

| window | start | P50 |
|---|---|---|
| fiscal FY27 | 2026-12 | $469,795 |
| calendar 2027 | 2027-01 | $475,874 |

delta **−$6,079 = −1.3%** — the fiscal window is one month less far along the
growth curve. Computed with common random numbers, and well inside a ±11.1%
band, but computed rather than assumed.

### Variance decomposition

`cost-projection variance config.json`:

| source | sd | share of sd | share of variance |
|---|---|---|---|
| growth (persistent, per year) | $40,612 | 97.0% | **94.0%** |
| jitter (iid, per month) | $8,066 | 19.3% | 3.7% |
| scenario toggles (Bernoulli) | $6,000 | 14.3% | 2.1% |
| **total** | **$41,881** | | |

Quadrature sum $41,838 vs measured $41,881. Despite `edge_variable` carrying a
`sig_m` of 0.18, monthly jitter accounts for under 4% of the variance. The band
is almost entirely a statement about growth uncertainty.

### Scenario toggle

`cost-projection scenario config.json --toggle marketplace=0.5`:

| branch | P50 |
|---|---|
| marketplace excluded (p=0) | $463,922 |
| marketplace included (p=1) | $475,922 |
| blended (p=0.5) | $469,795 |

swing $12,000, expected contribution $6,000. The blended P50 sits between two
discrete worlds — when a toggle is this size, quote both branches rather than
the blend alone.

## Why growth is drawn once per year

Drawing growth uncertainty **per month** instead reports a band of roughly ±4%.
That is not a forecast anyone should act on: iid monthly draws average down by
sqrt(12), so the annual band collapses. Drawing once per year and widening
`g_sd` to match measured volatility produces the honest ±22% width above.

Reproduce both on this exact config:

```bash
cost-projection simulate config.json --growth-draw per-month   # width 8.9% (+/-4.4%)
cost-projection simulate config.json --growth-draw per-year    # width 22.2% (+/-11.1%)
```

| growth draw | P10 | P50 | P90 | width |
|---|---|---|---|---|
| per month (bug) | $445,464 | $465,526 | $486,876 | 8.9% |
| per year (correct, default) | $424,083 | $469,795 | $528,400 | 22.2% |

The median moves by under 1%; the band more than doubles. A projection is a
statement about *uncertainty*, so getting the band right is the deliverable —
which is why per-year is the default and per-month prints an AUDIT MODE banner.

## Data hygiene on the way in

`cost-projection example series > series.json` ships a 3-series sample built
from the same shapes, so the traps are reproducible:

```bash
cost-projection detect-breaks series.json
```

- **`edge_variable`** ends with `2026-08: $600, partial: true` against a
  ~$10,000 month. Excluded from fits and from the break scan. With the flag
  removed, the fit reports an implied 12-month uncertainty of **±382%** and
  growth of **−99.1%/yr** — the same artefact behind the raw 390% figure damped
  above — and `detect-breaks` flags it as an unflagged suspect.
- **`compute_authoring`** contains an optimisation break: mean $13,360 over 5
  months steps down to **$8,642** over 6 months, a ÷1.55 step with `t = −7.6`.
  Full history reports −24%/yr; that is an artefact. `detect-breaks` recommends
  `--from 2026-02`, which yields a clean +4.2%/yr on a mean of $8,642.
- **`cdn_fixed`** is flat at $2,500 for 7 months, CV **exactly 0.0%**, and `fit`
  warns to model it as a fixed line with `sig_m: 0`.
