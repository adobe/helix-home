---
name: adobe-oncall
description: >-
  Interact with Adobe On-Call (ServiceNow-based pagerduty replacement) — list
  active on-call incidents, acknowledge, check who is on-call, view your
  upcoming shifts, and manage incident state. Use when the user mentions
  on-call, pagerduty, OCINC, incidents, shift schedule, acknowledge incident,
  escalation, or wants to check on-call status.
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
adobe-oncall incidents

# View a specific incident
adobe-oncall get OCINC2145403

# Acknowledge an incident
adobe-oncall ack OCINC2145403

# Check who is currently on-call for your groups
adobe-oncall whoisoncall

# View your upcoming shifts
adobe-oncall shifts

# Monday protocol output
adobe-oncall monday --limit 20 --date 7d
```

## Authentication

Session-based via Okta SSO. The user must be logged into
`adobe.service-now.com` with the On-Call workspace loaded in a browser tab.
The skill extracts the `g_ck` CSRF token from the page and makes API calls
via XHR from the page context.

If the session has expired: "Session expired — open adobe.service-now.com/x/adosy/on-call/home and try again."

## Available commands

### adobe-oncall incidents [--state=STATE] [--group=GROUP]

List active on-call incidents assigned to your groups.

States: `open` (1), `wip` (2), `re-open` (60), `resolved` (3), `closed` (4), `all`
Default: open + wip + re-open

### adobe-oncall get <OCINC_NUMBER|sys_id>

Get full incident details including short description, priority, state,
assignment group, assigned to, opened time, and related alerts.

### adobe-oncall ack <OCINC_NUMBER|sys_id>

Acknowledge an incident (sets acknowledged flag and assigns to you).

### adobe-oncall update <OCINC_NUMBER|sys_id> --state=STATE [--comment=TEXT]

Update incident state. Optionally add a work note.

### adobe-oncall whoisoncall [--group=GROUP]

Show who is currently on-call for your groups (or a specific group).

### adobe-oncall shifts

Show your upcoming on-call shifts.

### adobe-oncall monday [--limit N] [--date Nd]

Output active incidents in monday aggregator protocol format.

## Architecture

- **Incident table:** `x_adosy_adb_on_ca_incident` (prefix: OCINC)
- **Major incident table:** `x_adosy_mi_major_incident`
- **Schedule API:** UX Framework Databroker pipeline `get_on_call_summary_info`
  (definition: `1a7dd83d1b31b114fde1c8451a4bcba3`)
- **Access method:** XHR from ServiceNow workspace page context with `X-UserToken` header
- **On-Call app path:** `/x/adosy/on-call/home`

## Incident states

| Value | Label |
|-------|-------|
| 1 | Open |
| 2 | Work in Progress |
| 3 | Resolved |
| 4 | Closed |
| 60 | Re-Open |
