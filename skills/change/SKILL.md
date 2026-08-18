---
name: change
description: >
  Wrap any command in an Adobe ServiceNow Change Management Request (CMR) so the
  exact command line, working directory, exit code and output become the ticket's
  paper trail. `change [root params] run <command> [args...]` files the change,
  drives it New → Assess → Implement, executes the command, appends work notes and
  then closes it Successful or cancels it, exiting with the wrapped command's exit
  code. Also files, reads, annotates, transitions, closes and cancels changes on
  their own. Use whenever a production-affecting command needs change management
  around it — DNS flips, CDN switches, config pushes — or when the user mentions
  ServiceNow, CMR, CHG numbers, change request, change tickets, «file a change»,
  «open a CMR», «close the change», or asks what state a change is in. The default
  transport drives adobe.service-now.com in a logged-in browser tab and needs no
  credentials at all.
allowed-tools: bash
command: change
script: scripts/change.jsh
---

# change

```
change [root params] run <command> [args...]
```

Everything **before** `run` configures the change request. Everything **after**
`run` is executed verbatim. The exact command line is recorded on the ticket, and
`change` exits with the wrapped command's exit code, so it drops into a pipeline or
a CI step without changing its semantics.

Without `--confirm` every path is a dry run: it prints the payload it would POST
and the command it would execute, and calls nothing.

```bash
change --title="Flatten *.aem.live WRR to plain CNAME" \
       --plan-url=https://git.corp.adobe.com/…/runbook-switch-delivery.md#dns \
       --confirm \
       run gcloud dns records add aem-live '*.aem.live.' CNAME n.sni.global.fastly.net. --ttl 300
```

## What `run --confirm` does

Verified end to end against the live instance on CHG005369180 (`create` through `Closed`, no
manual intervention, exit 0).

1. **Create.** `POST /api/now/table/change_request` with the full Adobe field set, including
   the planned window: it starts at now + `--lead-time` (default 300 s) and lasts
   `--duration` (default 600 s). The start **must** be in the future, or ServiceNow silently
   reclassifies the change `type=latent`, which cannot reach Implement.
2. **Verify.** The record is read back with `sysparm_display_value=false`, including `type`:
   anything but `standard` is a hard error. The window is confirmed with up to three reads a
   second apart, then a `PATCH /api/now/table/change_request/{sys_id}` repair if needed. The
   `change_request_calendar` endpoint is never used — it writes `work_start`/`work_end`.
3. **`PATCH /api/sn_chg_rest/change/{sys_id}`** for `u_risk_type`, the one field create drops.
4. **New → Assess → Implement**, through the form. Each hop: check
   `GET /api/sn_chg_rest/change/{sys_id}/nextstates`, check the fields the hop needs are on the
   record, **reconcile the whole form against the record**, submit
   (`g_form.setValue('state', …)` + `gsftSubmit(…, 'adb_sysverb_update_and_stay')`), then
   re-read `state`, `type` and every tracked field. Any gate failure exits **90** with the
   wrapped command unexecuted and the change cancelled automatically.
5. **Run the command**, capturing argv, cwd, exit code and a bounded output tail.
6. **Paper trail** into `work_notes`, verified by a display read of the record.
7. **Close or cancel.** Exit 0 → `work_start`/`work_end` (floored to 60 s on the record, true
   duration in the notes), Review, then `close_code` + `u_impact_minutes` + `close_notes` and
   Closed. Non-zero → failure notes, then Canceled.
8. **Exit** with the wrapped command's own code.

### The flags that matter

| Flag | Default | Why it matters |
| --- | --- | --- |
| `--confirm` | — | **required for any write.** Without it everything is a dry run that prints the payload and the command and calls nothing |
| `--title=` | — | required for `run` and `create` |
| `--lead-time=` | `300` | seconds from now to the planned **start**. Must be > 0, or the change is reclassified `latent` and cannot reach Implement |
| `--duration=` | `600` | length of the planned window, in seconds |
| `--cso=none\|fix\|prevent` | `none` | «Change is related to an emergency» (`u_change_fixing_cso`). **Mandatory for New → Assess**; `none` means «not an emergency», which is a real value, not blank |
| `--instance=` | EDS Delivery prod instance | `u_service_offering_instance`; a `glide_list`, so it is also written into the form before every submit |
| `--via=servicenow\|ipaas` | `servicenow` | transport. The default needs **no secrets**; `ipaas` needs `IPAAS_API_KEY` plus the IMS triple and is unproven |
| `--keep-open` | off | stop at Review instead of closing |
| `--notes-max=` | `4000` | cap on captured output in the work notes |
| `--form-timeout=` | `30` | seconds to wait for the change form to become scriptable |
| `--state-timeout=` | `60` | seconds to wait for a state transition to land |

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | success, or a dry run |
| *n* | the wrapped command’s own exit code, propagated verbatim |
| `90` | **the CMR gate failed and the wrapped command never ran** — distinct on purpose, so a caller can tell it apart from the command failing |
| `1` | usage error, a refused value, or an unexpected error |

A pipeline masks the exit code (`$?` is the last command's), so branch with a redirect,
`set -o pipefail`, or `${PIPESTATUS[0]}`.

## Subcommands

| Command | Purpose |
| --- | --- |
| `run <cmd…>` | the whole lifecycle above |
| `create` | file a CMR and stop in New |
| `get <CHG…\|sys_id>` | show a change (`--json` for raw fields) |
| `notes <CHG…> "text"` | append work notes |
| `assess` / `implement` / `review` `<CHG…>` | **exactly one** hop each. These never write `work_start`, `work_end`, `close_code` or `u_impact_minutes` — only `run` and `close` do. Mandatory fields for the hop are checked first, so a missing one is an instruction, not a banner |
| `close <CHG…>` | close fields + Closed |
| `cancel <CHG…>` | Canceled |
| `states <CHG…>` | pretty-print `nextstates`, including which conditions fail |
| `form <CHG…>` | read-only diagnostic: open or reuse the change form, time how long it takes to become scriptable, and report what the form holds (including whether `u_change_approver` reached the form) |
| `config` | resolved non-secret configuration |

Everything that writes requires `--confirm`.

## Root params

Only `--flag=value` and bare `--flag` are accepted: `--flag value` would blur the
boundary between root params and the subcommand.

`--title=` (required for `run`/`create`) · `--description=` (defaults to the
command line) · `--plan-url=` · `--implementation-plan=` · `--backout-plan=` ·
`--test-plan=` · `--justification=` · `--risk-analysis=` · `--ci=` ·
`--instance=` · `--hosting-location=` · `--environment=` · `--tenant-type=` ·
`--customer-impact=` · `--complexity=` · `--reason=` · `--backout-type=` ·
`--validation=` · `--risk-type=` · `--cso=none|fix|prevent` (mandatory for New → Assess) · `--risk=` · `--sn-impact=` · `--urgency=` ·
`--scope=` · `--approver=` · `--deployer=` · `--requested-by=` · `--submitter=` ·
`--lead-time=` · `--duration=` · `--notes-max=` · `--state-timeout=` · `--form-timeout=` · `--impact-minutes=` ·
`--close-code=` · `--close-notes=` · `--keep-open` · `--no-normalise-choices` · `--via=` · `--confirm` ·
`--json`.

`CHANGE_VERBOSE=1` traces every HTTP call on stderr.

Resolution order for every setting: **flag → skill config (`.config`) →
environment variable → built-in default.** `change config` prints what won.

## Transports

**`--via=servicenow` (default) needs no secrets.** Every call runs in the page
context of a logged-in `adobe.service-now.com` tab, with
`X-UserToken: window.g_ck` — without that header ServiceNow requests hang instead
of returning 401. The tab is discovered with `playwright-cli tab-list`; if none is open, one is opened with
`playwright-cli open`. Form work needs a tab on `change_request.do` for that exact
`sys_id` — a list view will not do — so such a tab is reused if present and otherwise
opened, and only a tab `change` opened itself is closed afterwards. If the browser is not logged in,
`change` says so and does nothing. Full contract, state codes, field set and the
glide_list quirk: `references/servicenow.md`.

**`--via=ipaas` is headless but unproven.** It needs `IPAAS_API_KEY` plus the IMS
client triple (`IPAAS_IMS_CLIENT_ID`, `IPAAS_IMS_CLIENT_SECRET`, `IPAAS_IMS_CODE`),
none of which exist yet, so the path has never been executed. `change --via=ipaas`
without them refuses up front and names the missing values. Contract and the
secret-firewall blocker: `references/ipaas.md`.

## Guardrails

* Choice values are normalised to their **raw** form before any write. Labels and raw
  values render identically in the UI, but reports and business rules key on the raw value,
  so a label written verbatim is silent data damage (the earlier hand-made CHG005367969
  carries exactly that in two fields). Pass either form: `--reason="Bug Fixes"` and
  `--reason=bug_fixes` both send `bug_fixes`, and an unrecognised value is refused with the
  allowed list. `change config` prints every choice as `raw (Label)`. The iPaaS contract is
  the opposite — it takes labels — so values are not shared between transports.
* A transition is a form submit, and a form submit posts everything. `change` snapshots the
  record, fills every widget it is responsible for from the record, submits, then verifies that
  no tracked field was blanked. A blanked field stops the run and is reported with its previous
  value: that is data loss on a production record, and retrying would repeat it.
* Some transitions in the Adobe model are automatic (`Assess → Review` fires by itself once
  its conditions pass). When the state ends up further along than requested, `change` says
  which state was reached and that ServiceNow, not `change`, moved it.
* A change that is not properly scheduled ahead of time gets reclassified `type=latent` **at
  its first hop**, which runs a different state machine: `Assess → Implement` is refused,
  `Assess → Review` fires automatically, and `u_environment`,
  `u_service_offering_instance` and `u_hosting_location` are cleared in the same update — so
  the failure surfaces two steps later as «mandatory field not filled in». Hence
  `--lead-time`, and the `type` read-back after create and after every hop. Editing the window
  afterwards does not undo it. This is the instance's sharpest edge; the evidence table is in
  `references/servicenow.md`.
* Approvals are never created. They are workflow-only in this instance:
  `sysapproval_approver` writes get their references stripped and cannot be
  deleted. Only `u_change_approver` on the change itself is set.
* No write is believed on its HTTP status alone. Every write is re-read with
  `sysparm_display_value=false`; a transition that did not move surfaces the
  ServiceNow banner text verbatim.
* The captured output in `work_notes` is capped (`--notes-max`, default 4000
  chars): head and tail are kept, the middle is dropped, and the note says how
  many characters were omitted.
* Output of the wrapped command is not streamed incrementally: the `.jsh` runtime
  buffers a child process and returns it on exit, so `change` echoes stdout and
  stderr in full the moment the command finishes.
* Secrets are never printed, never written to disk by this skill, and iPaaS error
  reporting is limited to `error` / `error_description`.
