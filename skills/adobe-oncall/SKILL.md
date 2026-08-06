---
name: adobe-oncall
description: >-
  Interact with Adobe On-Call (ServiceNow-based pagerduty replacement) — list
  active on-call incidents, acknowledge, check who is on-call, view your
  upcoming shifts, and manage incident state. Use when the user mentions
  on-call, pagerduty, OCINC, incidents, shift schedule, acknowledge incident,
  escalation, or wants to check on-call status. Can also watch for new incidents
  and auto-launch an investigation scoop so the legwork is done before you ack.
allowed-tools: bash
---

# Adobe On-Call

Direct API access to Adobe's On-Call incident management system, built on
ServiceNow at `adobe.service-now.com/x/adosy/on-call/home`.

Requires an active ServiceNow session in the browser (Okta SSO). The skill
uses the ServiceNow Table API and UX Framework Databroker from the workspace
page context.

## Quick start

```bash
# List active on-call incidents
oncall incidents

# View a specific incident
oncall get OCINC2145403

# Acknowledge an incident
oncall ack OCINC2145403

# Check who is currently on-call
oncall who

# View your upcoming shifts (next 14 days by default)
oncall shifts

# Auto-investigate new incidents via a scoop (legwork done before you ack)
oncall watch --scoop oncall-investigator --interval 2
oncall unwatch

# Monday protocol output
oncall monday --limit 20 --date 7d
```

## Authentication

Session-based via Okta SSO. The user must be logged into
`adobe.service-now.com` with the On-Call workspace loaded in a browser tab.
The skill extracts the `g_ck` CSRF token from the page and makes API calls
via XHR from the page context.

If the session has expired: "Session expired — open adobe.service-now.com/x/adosy/on-call/home and try again."

## Available commands

### oncall incidents [--state=STATE] [--group=GROUP]

List active on-call incidents assigned to your groups.

States: `open` (1), `pending` (-5), `wip` (2), `resolved` (6), `cancelled` (8), `re-open` (60), `all`
Default: open + wip + re-open

### oncall get <OCINC_NUMBER|sys_id>

Get full incident details including short description, priority, state,
assignment group, assigned to, opened time, and related alerts.

### oncall ack <OCINC_NUMBER|sys_id>

Acknowledge an incident (sets acknowledged flag and assigns to you).

### oncall update <OCINC_NUMBER|sys_id> --state=STATE [--comment=TEXT]

Update incident state. Optionally add a work note. State names: `open`, `pending`,
`wip`, `resolved`, `cancelled`, `re-open` (or pass a raw numeric value). To just add a
work note without changing state, pass only `--comment`.

### oncall who [--group=GROUP]

Show who is currently on-call across **both EMEA and NA rosters**.
Returns coverage (primary pager carrier) and shift (rotation slot) info.
Uses the calendar spans databroker to get real-time schedule data.

### oncall shifts [--days=N]

Show your upcoming on-call shifts (default 14-day window). Reports whether you're
on call now, when the current shift ends, and future shifts. Built on the calendar
spans databroker (the old summary-card pipeline returned no data).

### oncall watch --scoop <name> [--interval <min>] [--force]

Auto-investigate new incidents. Registers a cron task (default every 2 minutes)
routed to `<name>`; on each tick the scoop runs `oncall watch-poll` and, for any
**new** incident, kicks off a klickhaus investigation per the RCA playbook and posts
a findings work note — so by the time you manually ack, the legwork is underway. It
does **not** change incident state (that stays a human decision). On start, the
currently-active incidents are seeded as already-seen so only genuinely new ones
fire. `oncall watch` with no args shows the current watch; `--force` replaces it.

The investigator scoop needs the browser tabs the skills use (a logged-in
ServiceNow tab, and the klickhaus dashboard tab so klickhaus can auto-detect creds).

### oncall watch-poll [--json]

List on-call incidents not yet surfaced (dedup via a local seen-set); idempotent,
safe to run every tick. This is the detection engine the watcher scoop calls.

### oncall unwatch

Stop watching: deletes the cron task and clears watch state.

### oncall monday [--limit N] [--date Nd]

Output active incidents in monday aggregator protocol format.

## Architecture

- **Incident table:** `x_adosy_adb_on_ca_incident` (prefix: OCINC)
- **Major incident table:** `x_adosy_mi_major_incident`
- **Schedule/shifts API:** UX Framework Databroker pipeline `get_calendar_spans_1`
  (definition: `b90d6f7a1be2fd10fde1c8451a4bcba6`) — returns both EMEA and NA rosters;
  used by both `who` and `shifts`. (The old `get_on_call_summary_info` pipeline
  returned no data and is no longer used.)
- **EMEA roster:** `a99c33f58360c7d00479abe0deaad33d` (03:00–15:00 UTC)
- **NA roster:** `6f4df71c47f11610c49b3d54116d4335` (15:00–03:00 UTC)
- **Access method:** XHR from ServiceNow workspace page context with `X-UserToken` header
- **Watch:** cron task (`crontask`) routed to a scoop; detection/dedup in `watch-poll`.
  Runtime state lives in `/shared/` (`/shared/.oncall-watch.json`, `/shared/.oncall-watch-seen.json`)
  so the watcher scoop can write it too (`/workspace/skills` is read-only to scoops).
- **On-Call app path:** `/x/adosy/on-call/home`

## Incident states

Verified against the table's choice list / `g_form` (and a resolved record's `state.display_value`):

| Value | Label |
|-------|-------|
| 1 | Open |
| -5 | Pending |
| 2 | Work in Progress |
| 6 | Resolved |
| 8 | Cancelled |
| 60 | Re-Open |
