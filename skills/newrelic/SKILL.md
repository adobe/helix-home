---
name: newrelic
description: >-
  Use this when investigating New Relic alerts, synthetic monitor failures, or when you need NRQL against AEM Edge Delivery Services telemetry. Covers open alert issues, synthetic monitor health, scripted monitor source, per-request breakdowns of what a monitor is calling, secure credential inventory, and arbitrary NRQL/NerdGraph. Triggers on requests like "investigate this New Relic incident", "why is this monitor failing", "what's alerting", "run this NRQL", "show me the synthetic script", or when handed a one.newrelic.com/alerts/issue link.
allowed-tools: bash
---

# New Relic — alerts, NRQL and Synthetics

CLI for New Relic's NerdGraph API. Companion to `klickhaus`: klickhaus answers
"what is the CDN doing", `newrelic` answers "what is alerting, and why".

Everything runs through one GraphQL endpoint, so any command can be reproduced
by hand with `newrelic graphql`.

## Quick start

```bash
# Option A — headless, with a User API key (Profile > API keys in the UI)
newrelic login --key=NRAK-... --account=2429334

# Option B — no key, reuse an open one.newrelic.com tab you are logged into
newrelic login --from-tab

# What is on fire right now?
newrelic issues

# Synthetic monitor health across the account
newrelic monitors

# Drill into one monitor
newrelic checks "RUM Script Delivery" --range=3h
newrelic requests "RUM Script Delivery" --range=1h
newrelic script "RUM Script Delivery" > backup.js

# Arbitrary NRQL and NerdGraph
newrelic nrql "SELECT count(*) FROM SyntheticCheck FACET result SINCE 1 hour ago"
newrelic graphql "{ actor { user { name } } }"
```

## Available commands

| Command | Purpose |
|---------|---------|
| `login` | Store a User API key, or switch to tab-session mode |
| `accounts [FILTER]` | Accounts this identity can read (filter by name substring) |
| `issues` | Alert issues — `--state=ACTIVATED` (default), `CLOSED`, `CREATED` |
| `monitors` | All synthetic monitors with type, period, status, failing locations |
| `monitor <NAME\|GUID>` | One monitor in detail, including tags |
| `script <NAME\|GUID>` | Print a scripted monitor's source |
| `checks <NAME>` | Result counts, failure messages, per-location breakdown |
| `requests <NAME>` | Outbound URLs and HTTP status codes for a scripted monitor |
| `credentials` | Secure credential key names (values are never retrievable) |
| `nrql <QUERY>` | Run NRQL |
| `graphql <QUERY>` | Run raw NerdGraph |
| `set-period <NAME> <PERIOD>` | Change run frequency — needs `--confirm` |
| `set-script <NAME> --file=F` | Replace a scripted monitor's source — needs `--confirm`, backs up the live version first |

## Common flags

- `--account=N` — Account id (default: whatever `login` stored, else `2429334`)
- `--range=RANGE` — `5m`, `15m`, `30m`, `1h`, `3h`, `6h`, `12h`, `24h`, `3d`, `7d` (default: `1h`)
- `--limit=N` — Result cap
- `--file=PATH` — Read a query or script from a file
- `--json` — Raw JSON instead of a table
- `--backup=PATH` — Where `set-script` saves the version it is about to replace
- `--no-backup` — Skip that backup (the deploy aborts if a backup fails and this is not set)
- `--confirm` — Required by anything that writes

## Investigating a failing synthetic monitor

The fast path, in the order that actually narrows things down:

```bash
newrelic checks "MONITOR" --range=6h     # is it flapping or a clean break? what's the error string?
newrelic requests "MONITOR" --range=1h   # which URL is returning which status?
newrelic script "MONITOR"                # what does the code do with that status?
newrelic credentials                     # which secret does it depend on?
```

`checks` tells you *when* and *how consistently*. `requests` tells you *which
dependency*. Then the script tells you why a raw status became the message you
saw in the alert. A clean break across every location at the same minute means a
shared input changed (a credential, a quota), not the network — per-location or
per-IP problems fail unevenly.

## Architecture

- **Endpoint**: NerdGraph, `https://api.newrelic.com/graphql`
- **Auth, key mode**: User API key in the `Api-Key` header. Stored in the skill's
  gitignored `.config` via the `skill.config` bridge, never in the repo.
- **Auth, tab mode**: POSTs to the relative `/graphql` from an open
  `one.newrelic.com` tab, reusing its cookies. Requires the
  `newrelic-requesting-services: nr1-ui` header or the proxy rejects it.
- **Stale tabs**: a recreated tab leaves a dead CDP session behind while still
  appearing in `tab-list`. Tab mode remembers dead ids and retries once against
  another matching tab, which is why a long-lived shell keeps working.
- **Events**: `SyntheticCheck` (one per monitor run per location: `result`,
  `error`, `locationLabel`) and `SyntheticRequest` (one per outbound HTTP call:
  `URL`, `responseCode`, `domain`, `minionPublicIp`, timings)
- **Entities**: monitors are `domain = 'SYNTH' AND type = 'MONITOR'`, secure
  credentials are `domain = 'SYNTH' AND type = 'SECURE_CRED'`

## Don't

- Don't expect a response body from `SyntheticRequest` — it stores status codes
  and timings only. To learn *why* GitHub returned 403, the script has to log the
  body itself; a bare `throw new Error('API ' + statusCode)` throws that away.
- Don't pass `accountId` to `syntheticsUpdateScriptApiMonitor`. It takes only
  `guid` and `monitor`, and anything else is a schema error. Fields omitted from
  `monitor` are preserved.
- Don't query `errors { type }` on a Synthetics mutation result. `SyntheticsError`
  has `description` only.
- Don't look for secure credentials under `account.synthetics` — there is no such
  field. They are entities.
- Don't try to cache anything between synthetic runs. Scripted monitors are
  stateless, so ETag/`If-None-Match` tricks cannot work, and per-run cost is the
  only lever you have besides frequency and location count.
- Don't run `newrelic accounts` unfiltered on a large identity and expect it to
  be readable — some logins can see hundreds of accounts.
- Don't pass `--no-backup` to `set-script` casually. By default the live script
  is snapshotted to `/shared/newrelic-script-backups/<slug>-<timestamp>.js` and a
  failed backup aborts the deploy, so a monitor is never overwritten blind.
- Don't trust `String.length` as a byte count when handling scripts. These files
  contain box-drawing characters, so 12,886 characters is 15,042 bytes.
