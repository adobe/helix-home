---
name: newrelic
description: >-
  Use this when investigating New Relic alerts, synthetic monitor failures, or when you need NRQL against AEM Edge Delivery Services telemetry. Covers open alert issues, synthetic monitor health, scripted monitor source, per-request breakdowns of what a monitor is calling, secure credential inventory, monitor period and script changes, and arbitrary NRQL/NerdGraph, all through the official New Relic CLI (`newrelic`). Triggers on requests like "investigate this New Relic incident", "why is this monitor failing", "what's alerting", "run this NRQL", "show me the synthetic script", or when handed a one.newrelic.com/alerts/issue link.
allowed-tools: bash
---

# New Relic — alerts, NRQL and Synthetics

Use the official New Relic CLI, `newrelic`. Companion to `klickhaus`: klickhaus
answers "what is the CDN doing", New Relic answers "what is alerting, and why".

Almost everything here is one of two calls: `newrelic nrql query` for event data
and `newrelic nerdgraph query` for everything else (issues, monitors, scripts,
credentials, mutations). The EDS account is `2429334` (`DX_Project_Helix`).

## Setup

```bash
# Install. In SLICC:
pnpm add -g @ai-ecoverse/wasi-newrelic
# Elsewhere: New Relic's own release, github.com/newrelic/newrelic-cli

newrelic version
```

The CLI needs a User API key (`NRAK-…`, Profile > API keys in the New Relic UI).
Give it through the environment rather than a stored profile:

```bash
export NEW_RELIC_API_KEY=...         # in SLICC, a vault placeholder, never the value
export NEW_RELIC_ACCOUNT_ID=2429334
```

`newrelic profile configure` stores the key in `~/.newrelic` instead; avoid that
on shared machines and in agent transcripts. A missing key fails with "could not
initialize New Relic client"; a wrong one with "401 … Invalid credentials".

## Recipes

All `nerdgraph query` calls take the query as the first argument and
`--variables` as JSON. Output is JSON by default.

### What is alerting

```bash
newrelic nerdgraph query 'query($account: Int!) { actor { account(id: $account) { aiIssues {
  issues(filter: {states: ACTIVATED}) { nextCursor issues { issueId title state priority activatedAt entityNames } } } } } }' \
  --variables '{"account": 2429334}'
```

Use `CLOSED` or `CREATED` for other states.

### Monitors

```bash
# Every monitor with type, period, status and failing locations
newrelic nerdgraph query 'query($query: String!, $cursor: String) { actor { entitySearch(query: $query) {
  results(cursor: $cursor) { nextCursor entities { guid name
    ... on SyntheticMonitorEntityOutline { monitorType period monitorSummary { status locationsFailing locationsRunning } } } } } } }' \
  --variables '{"query": "domain = '\''SYNTH'\'' AND type = '\''MONITOR'\'' AND accountId = 2429334"}'

# A monitor's GUID from its name (use the same query with `AND name = '…'`)

# One monitor in detail, with tags
newrelic nerdgraph query 'query($guid: EntityGuid!) { actor { entity(guid: $guid) { name guid accountId tags { key values }
  ... on SyntheticMonitorEntity { monitorType period monitorSummary { status locationsFailing locationsRunning } } } } }' \
  --variables '{"guid": "GUID"}'

# A scripted monitor's source
newrelic nerdgraph query 'query($account: Int!, $guid: EntityGuid!) { actor { account(id: $account) {
  synthetics { script(monitorGuid: $guid) { text } } } } }' \
  --variables '{"account": 2429334, "guid": "GUID"}'

# Secure credential names (values are never retrievable)
newrelic nerdgraph query 'query($query: String!, $cursor: String) { actor { entitySearch(query: $query) {
  results(cursor: $cursor) { nextCursor entities { guid name } } } } }' \
  --variables '{"query": "domain = '\''SYNTH'\'' AND type = '\''SECURE_CRED'\'' AND accountId = 2429334"}'
```

`entitySearch` returns at most 200 entities per page. When `nextCursor` is not
null, repeat the query with `"cursor": "<nextCursor>"` in the variables until it
is.

### Checks and requests

```bash
M="RUM Script Delivery"
# Is it flapping or a clean break?
newrelic nrql query -a 2429334 -q "SELECT count(*) FROM SyntheticCheck WHERE monitorName = '$M' FACET result SINCE 6 hours ago"
# What does it fail with?
newrelic nrql query -a 2429334 -q "SELECT count(*) FROM SyntheticCheck WHERE monitorName = '$M' AND result = 'FAILED' FACET error SINCE 6 hours ago LIMIT 10"
# Where?
newrelic nrql query -a 2429334 -q "SELECT count(*) FROM SyntheticCheck WHERE monitorName = '$M' FACET locationLabel, result SINCE 6 hours ago"
# Which outbound URL returns which status?
newrelic nrql query -a 2429334 -q "SELECT count(*) FROM SyntheticRequest WHERE monitorName = '$M' FACET URL, responseCode SINCE 1 hour ago LIMIT 25"
```

Escape a single quote in a monitor name as `\'` inside NRQL.

### Changing a monitor

These change a live monitor. Ask first, and save the current script before you
replace it (see "Monitors" above).

```bash
# Run frequency. Values: EVERY_MINUTE, EVERY_5_MINUTES, EVERY_10_MINUTES, EVERY_15_MINUTES,
# EVERY_30_MINUTES, EVERY_HOUR, EVERY_6_HOURS, EVERY_12_HOURS, EVERY_DAY
newrelic nerdgraph query 'mutation($guid: EntityGuid!) { syntheticsUpdateScriptApiMonitor(guid: $guid,
  monitor: {period: EVERY_15_MINUTES}) { errors { description } monitor { name period status } } }' \
  --variables '{"guid": "GUID"}'

# Replace a scripted monitor's source
newrelic nerdgraph query 'mutation($guid: EntityGuid!, $script: String!) { syntheticsUpdateScriptApiMonitor(guid: $guid,
  monitor: {script: $script}) { errors { description } monitor { name period status } } }' \
  --variablesFile vars.json     # {"guid": "GUID", "script": "<file contents as a JSON string>"}
```

The update mutation depends on the monitor type: SIMPLE ->
`syntheticsUpdateSimpleMonitor`, BROWSER -> `syntheticsUpdateSimpleBrowserMonitor`,
SCRIPT_BROWSER -> `syntheticsUpdateScriptBrowserMonitor`, SCRIPT_API ->
`syntheticsUpdateScriptApiMonitor`. Only the two scripted types accept `script`.
Check `errors` in the answer: a rejected update comes back there, not as a GraphQL error.

## Investigating a failing synthetic monitor

The fast path, in the order that actually narrows things down:

1. Checks by result, then by error: is it flapping or a clean break, and what's
   the error string?
2. Requests by URL and status: which dependency returns what?
3. The script: what does the code do with that status?
4. Credentials: which secret does it depend on?

Checks tell you *when* and *how consistently*. Requests tell you *which
dependency*. Then the script tells you why a raw status became the message you
saw in the alert. A clean break across every location at the same minute means a
shared input changed (a credential, a quota), not the network — per-location or
per-IP problems fail unevenly.

## Data model

- **Events**: `SyntheticCheck` (one per monitor run per location: `result`,
  `error`, `locationLabel`) and `SyntheticRequest` (one per outbound HTTP call:
  `URL`, `responseCode`, `domain`, `minionPublicIp`, timings)
- **Entities**: monitors are `domain = 'SYNTH' AND type = 'MONITOR'`, secure
  credentials are `domain = 'SYNTH' AND type = 'SECURE_CRED'`
- **Issues**: `actor.account.aiIssues.issues`, filtered by state, paginated by
  `nextCursor` like `entitySearch`

## Don't

- Don't expect a response body from `SyntheticRequest` — it stores status codes
  and timings only. To learn *why* GitHub returned 403, the script has to log the
  body itself; a bare `throw new Error('API ' + statusCode)` throws that away.
- Don't pass `accountId` to the `syntheticsUpdate*Monitor` mutations. They take only
  `guid` and `monitor`, and anything else is a schema error. Fields omitted from
  `monitor` are preserved.
- Don't query `errors { type }` on a Synthetics mutation result. `SyntheticsError`
  has `description` only.
- Don't look for secure credentials under `account.synthetics` — there is no such
  field. They are entities.
- Don't try to cache anything between synthetic runs. Scripted monitors are
  stateless, so ETag/`If-None-Match` tricks cannot work, and per-run cost is the
  only lever you have besides frequency and location count.
- Don't list every account an identity can see (`actor { accounts { id name } }`)
  and expect it to be readable — some logins can see hundreds of accounts.
- Don't replace a script without saving the live version first.
- Don't trust `String.length` as a byte count when handling scripts. These files
  contain box-drawing characters, so 12,886 characters is 15,042 bytes.
