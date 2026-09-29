# Methodology

Why this tool is shaped the way it is. Every number quoted below was produced by
running the shipped CLI; the commands are given so you can re-derive them.

## 1. Growth is drawn once per year, not once per month

This is the whole point of the skill.

Two different things move a cost line:

- **Persistent growth uncertainty.** You do not know next year's growth rate. If
  you guess 20% and the truth is 35%, you are wrong in the *same direction every
  single month*. The error compounds across the year instead of cancelling.
- **Monthly jitter.** Traffic wobbles month to month. Those wobbles are roughly
  independent, so across 12 months they partly cancel.

For a sum of 12 months, an iid monthly shock of log-sd `sig_m` contributes about
`sig_m/sqrt(12)` to the *annual* relative sd — it is divided by 3.46. A
persistent growth error contributes about `g_sd/2` to the annual total,
undiminished, because every month of the year is shifted the same way.

So the model draws `g ~ Normal(g_mu, g_sd)` **once per simulated year** and holds
it across all 12 months:

```
g   ~ Normal(g_mu, g_sd)                      once per component, per year
level_m = base × exp(g × (m − 0.5)/12) × exp(sig_m × Z_m − sig_m²/2)
```

Over a horizon longer than 12 months, `g` is redrawn for each new simulated
year and the **completed years' growth stays baked in**: the log growth accrued
to a month is the running sum of the monthly increments, not the current year's
rate re-applied to the whole elapsed period. Getting this wrong makes a path
jump discontinuously at every year boundary — with year-one growth of +20% and
year-two of −10%, month 12 sits at $1,211 (per $1,000 of base) and month 13
must sit just above it at $1,216, not fall to $901.

`Z_m` is a fresh standard normal each month. The `− sig_m²/2` correction makes
the jitter median-preserving, so adding volatility does not quietly inflate the
central estimate. The mid-month exponent `(m − 0.5)/12` places month `m` half a
month into its own growth, so a 12-month total carries exactly half a year of
growth on average — the right convention when `base` is the level at the first
month of the window.

### The failure mode, measured

Drawing growth freshly each month is a natural-looking mistake: it *feels* like
more randomness, so it feels conservative. It is the opposite. The per-month
draws average down by sqrt(12) and the annual band collapses.

Reproduce it on the worked example:

```bash
cost-projection example config > config.json
cost-projection simulate config.json --growth-draw per-month   # the bug
cost-projection simulate config.json --growth-draw per-year    # correct, default
```

| growth draw | P10 | P50 | P90 | p10–p90 width |
|---|---|---|---|---|
| per **month** (bug) | $445,464 | $465,526 | $486,876 | **8.9%** of median (±4.4%) |
| per **year** (correct) | $424,083 | $469,795 | $528,400 | **22.2%** of median (±11.1%) |

Same inputs, same seed. The median barely moves; the band more than doubles.
A projection that reports ±4% on a one-year-ahead cloud forecast is not being
conservative, it is being wrong — the fix is this change plus widening `g_sd` to
match measured volatility. `per-month` is retained only so a reader can
reproduce the error; it prints a loud AUDIT MODE banner and its band must never
be quoted.

## 2. Variance decomposition

`cost-projection variance config.json` isolates each source by re-running with
the others zeroed. On the worked example:

| source | sd | share of sd | share of **variance** |
|---|---|---|---|
| growth (persistent, per year) | $40,612 | 97.0% | **94.0%** |
| jitter (iid, per month) | $8,066 | 19.3% | 3.7% |
| scenario toggles (Bernoulli) | $6,000 | 14.3% | 2.1% |
| total | $41,881 | | |

Quadrature sum $41,838 vs measured $41,881 — the sources are near-independent,
which is the check that the decomposition is meaningful.

Read this as an instruction about where to spend effort. `edge_variable` carries
`sig_m = 0.18`, a large monthly wobble, and it still contributes almost nothing
to the annual spread. Arguing about monthly noise is wasted work; arguing about
the *growth range* is the entire ballgame. Note that shares of sd do not sum to
100% and shares of variance do — quote the variance shares.

The general result: jitter's share of the annual sd is roughly
`(sig_m/sqrt(12)) / (g_sd/2)`. With `g_sd` in the 0.3–0.45 range typical of a
contaminated cloud series, jitter has to be implausibly large to matter.

## 3. Regime breaks

A regime break is a deliberate or structural level shift: an optimisation
lands, a service is wound down, a contract steps, a project's scope changes.
Fitting growth across one produces a number that is not merely imprecise but
meaningless.

Two shapes that recur:

- A line that ran about $80/mo for a year, jumped to $5,200 when a service was
  switched on and settled at $900 once it was tuned. Fitted across the whole
  history it reports an annualised growth in the thousands of percent. Nothing
  about that number is usable.
- A line that ramped $11,000 → $15,000/mo and was then deliberately halved by an
  optimisation. Full history says −24%/yr; the post-break window says +4%/yr.
  The full-history figure is an artefact of averaging across the step.

**A sign disagreement between full-window and trailing-6-month growth is the
signature.** `fit` warns on it, and withholds its suggested stanza.

`detect-breaks` tests every split point with ≥3 points per side, comparing
segment means and computing a Welch t on the log levels. Flag on either a large
ratio (default 1.5x) or a statistically crisp modest step (`|t| > 4` and ≥1.25x)
— the second clause catches a contract change that is unambiguous but small.
The default threshold is deliberately 1.5 rather than 2.0: on the sample series
a genuine optimisation break is a ÷1.55 step with `t = −7.6`, and a 2.0
threshold misses it on the ratio rule alone.

Then refit forward of the break. On the sample, `--from 2026-02` turns a
meaningless −24%/yr into a post-break mean of $8,642 over 6 months at +4.2%/yr.
**A short clean window beats a long contaminated one** — but say so, and widen
`g_sd` to reflect how little data you have.

## 4. Partial billing months

The single most destructive data artefact. A current-month figure of $600
against a ~$10,000 typical month is an incomplete billing period, not a 94%
decline. Left in a fit it produces, measured on the shipped sample series:

- implied 12-month uncertainty of **±382%**
- annualised growth of **−99.1%/yr**

Mark such points `"partial": true` (or `"estimated": true`) and fits exclude
them by default. `--include-partial` exists but is almost always wrong.

Because analysts forget, `detect-breaks` also reports **unflagged suspects**:
any point below 35% of its trailing 6-month median that carries no flag, with a
sharper hint when it is the last point. And flagged points are excluded from the
break scan itself — an included partial month invents a phantom step down at the
tail and hides the real break.

## 5. Fixed vs usage

Contractual subscriptions do not grow with traffic — a contract line measures a
CV of **exactly 0.0%** across 7–8 months. Averaging such a line together with
usage lines understates the volatility of the usage part and invents volatility
in the fixed part.

Model them separately:

- **fixed**: `sig_m: 0`, and `g_sd` representing *renewal uplift risk only*
  (0.08–0.15 is reasonable for a line that steps at renewal).
- **usage**: both `g_sd` and `sig_m` from the measured series.

`fit` warns when it sees a CV of exactly 0, because that is the fingerprint.

## 6. Damping discipline

`fit` gives you `implied_12m_uncertainty = mom_log_sd × sqrt(12)`. When the
series is clean, use it. When it is contaminated, the raw figure is nonsense and
must be damped — but never silently.

On the worked example:

| line | raw implied 12m | applied `g_sd` | why |
|---|---|---|---|
| `cdn_usage` | 12% | 0.14 | clean series, CV 3.1% — undamped |
| `compute_authoring` | 88% | 0.35 | contaminated by an optimisation break and a partial month |
| `platform_prod` | 80% | 0.35 | spans a project-scope change |
| `edge_variable` | 390% | 0.45 | partial-month artefact; damped to the clean-window CV of 19% scaled to a year |

A `damping` block therefore **requires** a non-empty `why`. `simulate` exits
non-zero without it, and `fit --explain` prints every rationale. The point is
not bureaucratic: damping is the step where an honest band becomes a dishonest
one, so the reason has to survive next to the number.

Damp *toward the post-break dispersion*, not toward a number you like. If you
cannot articulate a `why`, the correct move is to widen, not to damp.

## 7. Fiscal vs calendar year

A Dec–Nov fiscal year — FY27 = Dec 2026 – Nov 2027 — is not Jan–Dec 2027. Under
compounding growth a window one month earlier sits one month less far along the
growth curve, so it is cheaper.

`--fiscal-year-start 12` computes both windows using **common random numbers**
(the same seed and therefore the same draws), so the difference is a clean
comparison rather than two independent Monte Carlo estimates with their own
noise. On the worked example the fiscal window comes in $6,079 below the
calendar one, **−1.3%** — well inside a ±11.1% band, but computed rather than
assumed. Report the difference; do not skip the fiscal window because "it is
probably small".

## 8. Reading the band: empirical percentiles, not a confidence interval

The output is an **empirical percentile band over simulated futures**. P90 means
"90% of simulated years came in below this, *given these assumptions*". It is
not:

- a confidence interval on a fitted parameter (there is no sampling
  distribution here — the spread is assumption-driven, not data-driven);
- a probability statement about reality (it is conditional on `g_mu`, `g_sd` and
  the toggle probabilities being right);
- a bound. Nothing stops a new workload from landing in March.

The practical consequence: **the band is only as honest as the widest `g_sd` you
were willing to write down.** Monte Carlo noise is not the limiting factor — at
200,000 runs, reseeding moves the P50 of the worked example by well under 0.01%,
while a 0.1 change in one `g_sd` moves the band by
percent. Do not add runs hoping for precision; interrogate the assumptions.

Hence the narrow-band warning. A one-year-ahead cloud-cost band tighter than
about ±15% (p10–p90 width < 15% of median, scaled by `sqrt(horizon/12)`) is
almost always a modelling artefact rather than genuine confidence — most often
growth drawn per month, or a `g_sd` copied from a suspiciously clean window.

## 9. Implementation notes

- PRNG is a seeded mulberry32; normals come from the Marsaglia polar method with
  the second variate cached. `--seed` makes any band exactly reproducible, which
  matters when a projection has to be defended months later.
- Percentiles are linear-interpolated over the sorted sample.
- `--runs` is parsed with `Number`, not `parseInt`, so `2e5` means 200,000 and
  not 2.
- Component growth draws are independent. If you believe two components share a
  driver (one traffic curve behind both CDN and compute), the true band is
  *wider* than reported: model them as one component, or widen both `g_sd`s.
  This is the main known conservatism in the model and the first thing to
  revisit if a projection needs to be defended hard.
