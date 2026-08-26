# ServiceNow transport contract (`--via=servicenow`, the default)

Instance: `https://adobe.service-now.com`. No secrets, no API key, no OAuth: every
call is made **in the page context of a logged-in tab**, so the session cookie and
the CSRF token do the authenticating.

## Transport rules

1. **Find the tab, never hardcode it.** `playwright-cli tab-list`, take the first
   entry whose URL contains `adobe.service-now.com`. If none exists, open one:
   `playwright-cli open https://adobe.service-now.com/nav_to.do?uri=change_request_list.do`
   and parse `targetId: <ID>` out of the output.
2. **`X-UserToken: window.g_ck` on every `/api/` call.** Without it requests
   **hang** rather than returning 401. Read the token with a page eval of
   `window.g_ck` (72 chars on this instance).
3. **Retry once on a stale CDP session.** `CDP error: Session with given id not
   found (-32001)` and `No target with given id found (-32602)` mean the tab is
   gone: drop the cached tab and token, open a fresh one, retry exactly once.
4. **The form takes seconds to become scriptable — poll, never check once.** After
   `playwright-cli open`/`goto` on `change_request.do`, `g_form` and `gsftSubmit` appear
   several seconds later; measured between 2.7 s and 10 s on this instance. Poll
   `typeof g_form !== 'undefined' && typeof gsftSubmit === 'function' && document.readyState
   === 'complete'` every 500 ms up to a budget (this skill: `--form-timeout`, default 30 s)
   and report how long it waited when it gives up.
5. **A read straight after a write can still show the old value.** The planned-window PATCH
   is the known case: it succeeds, and the next read can still show `start_date` empty.
   Verify with a few reads a second apart before concluding anything failed.
6. **Target ids are full 32-character strings.** `playwright-cli eval --tab <id>` rejects an
   abbreviated prefix with `CDP error: No target with given id found (-32602)`. Take ids
   from `tab-list` verbatim and never truncate them for display and reuse.
7. **Pick a form tab by URL, not by host.** Several ServiceNow tabs are usually open and most
   are list views or other records with no `g_form` for the record in hand. Match
   `change_request.do` *and* the target `sys_id`; open a tab only if nothing matches, and
   close only what you opened.
8. **Verify every write with `sysparm_display_value=false`.** A read with
   `sysparm_display_value=all` once reported a value the server did not have.
   Raw values are the only trustworthy read.
9. **A transition is a form submit, so reconcile the whole form first.** `gsftSubmit` posts
   every widget; an empty one blanks a populated record field regardless of what the hop
   requires. Snapshot the record, reconcile, submit, then verify nothing was blanked.
10. **Read `type` back after every create.** The reclassification to `latent` is silent and
   its symptoms surface later, at an apparently unrelated hop.
11. **Some transitions are automatic.** `Assess → Review` has `automatic_transition: true` and
   fires by itself once its conditions pass, so a state can legitimately end up *further*
   along than requested. Treat «further along the lifecycle» as success and say who did it;
   treat «did not move» as failure.
12. **The session token is not always readable.** `window.g_ck` is empty on a tab that is
   mid-navigation — which is exactly what the form tab looks like just after `gsftSubmit`.
   Retry the read, prefer a tab that is not the one being submitted, and only conclude
   «logged out» if the URL is a sign-in/SSO page.
13. **Never trust HTTP 200 on a transition.** Re-read `state`. If it did not move,
   scrape the banner (`#output_messages`, `.outputmsg_error`, `.outputmsg`,
   `.notification-error`, `.alert-danger`, `#status_messages`) and surface that
   text verbatim — it is the only place ServiceNow explains a refusal.

## `gsftSubmit` posts the whole form, so an empty widget blanks a populated field

This is the mechanism behind almost every field-related symptom on this instance, and it is
worth stating on its own because it is not obvious from any API contract.

A state transition is a **form submit**. `gsftSubmit(null, g_form.getFormElement(), …)` posts
**every field on the form**, so any widget that is empty in the DOM is written back to the
record as empty — even if the record held a value, and even if the field has nothing to do with
the transition being made.

Observed on CHG005368815, a `standard` change with a correct future window:

```
sys_history_line #0 (create)  u_service_offering_instance → EDS Delivery (AEM Cloud - AWS - …)
                              u_environment              → production
                              u_hosting_location         → USA1
   only write between #0 and the failure: the New → Assess hop
after the hop                 u_service_offering_instance = ""
                              u_environment              = ""
                              u_hosting_location         = ""
   survived: u_tenant_type=Multi, cmdb_ci=a45e8458…, u_change_approver=c6c2bfff…
```

The `New → Assess` hop requires none of those three fields, so a reconciliation driven by the
*target hop's* requirements does nothing on that hop — and the submit blanks them. The next hop
then fails with «The following mandatory fields are not filled in: Instance(s)» about a field
the record had five seconds earlier.

### A blanked record cannot be closed, and the form cannot fix it

Reconciliation fills the form **from the record**. If the record itself has been blanked — by an
older client, or by any earlier save that posted an empty widget — there is nothing to hydrate
from, and every hop is refused for a field the record no longer has. CHG005368783 sat in
`Review` refusing to close with «The following mandatory fields are not filled in: Instance(s)»
until `u_service_offering_instance`, `u_hosting_location`, `u_environment` and
`u_change_fixing_cso` were restored with a Table API PATCH:

```
PATCH /api/now/table/change_request/{sys_id}
{ "u_service_offering_instance": "d0ae449893ffce50b7aef1e01bba1049", … }
```

That write works in any state, including `Review`, and needs no form. It is what
`change repair <CHG…> --confirm` does, restricted to the tracked fields and only where they are
empty.

`u_environment` does survive the create call — `sys_history_line` update #0 shows
`u_environment ""→"production"` on CHG005368567, CHG005369180 and CHG005370120 — while
`u_risk_type` is derived and dropped. Since one read decides it, the safe implementation is to
read both back after create and re-send whatever came back empty, rather than trusting either
observation permanently.

Note also that the form's mandatory set is validated on **every** save, not only on the hop that
conceptually owns the field: `Instance(s)` blocked a `Review → Closed` hop. So a hop preflight
has to check the always-mandatory fields (`u_service_offering_instance`,
`u_change_fixing_cso`, `u_tenant_type`) regardless of which hop it is.

### The rule

**Before every form submit, reconcile the whole form against the record.** For each field the
wrapper is responsible for, compare the widget with the record and write the record's value into
the widget when the widget is empty and the record is not. Not per-hop-requirement: per submit.

The fields this skill tracks are exactly the ones it sets at create:
`u_service_offering_instance`, `u_change_approver` (both `glide_list`), `u_hosting_location`,
`u_environment`, `u_tenant_type`, `cmdb_ci`, `u_change_fixing_cso`.

Two mechanisms are needed, because the widgets differ:

* `g_form.setValue(field, value[, display])` works for plain fields, choices and references, and
  keeps the form's own model consistent. It does **not** work for `glide_list`.
* the DOM nodes — hidden input `change_request.<field>`, mirror
  `sys_display.change_request.<field>`, selected `<option>` on `select_0change_request.<field>` —
  are what `glide_list` needs. Not every field renders all three, so absent nodes are tolerated.

This skill attempts both, in that order, in a single page call, then reads each field back.

**A field the form does not render is not at risk.** On the `ADBChangeForm` view
`u_change_fixing_cso` has neither a `change_request.u_change_fixing_cso` node nor a
`g_form` control, while the record holds `No - non-emergency`. A submit does not post it, so it
survives — and treating «cannot be written» as a blocker there would refuse every hop. The
reconciliation therefore distinguishes *absent from the form* (safe, reported as «not at risk»)
from *present but empty* (dangerous, blocks the submit). Presence is tested as «a DOM node
exists, or `g_form.getControl(field)` returns something».

### And verify afterwards

If a field the record held is empty after the submit, that is **data loss on a production
record**, not a transient error. Report it with the previous values and stop; do not retry,
because a retry posts the same incomplete form again. Two guards:

* **before** the submit: a tracked field the record has but the form will not take blocks the
  submit entirely («the form would not accept … so nothing was submitted»);
* **after** the submit: the record is re-read and compared against a snapshot taken before it.

One nuance worth keeping straight: a latent reclassification clears the same three fields in the
same update. So the post-submit check reads `type` first and reports the reclassification when
that is what happened, rather than blaming the submit for it.

## The most important fact about this instance: the latent reclassification

A change can be reclassified from `type=standard` to `type=latent` (label «Latent (Violated)»,
raw value `latent`) by a business rule. Latent changes run a **different state machine**, and
that single fact explains three tickets' worth of confusing symptoms.

```
the change is not properly scheduled ahead of time
        ↓  fires at the FIRST HOP (New → Assess), silently, no banner
type: standard → latent   AND   u_environment, u_service_offering_instance,
                                u_hosting_location are CLEARED in the same update
        ↓
Assess → Implement is REFUSED
   «Change model 'Adobe Change Model' prevented state transition from Assess to Implement»
   «Invalid update»
Assess → Review fires AUTOMATICALLY instead
        ↓
the next hop complains about a field the record no longer has:
   «The following mandatory fields are not filled in: Instance(s)»
```

### The evidence, from `sys_history_line`

| ticket | window present at create? | planned start minus create | window edited later? | `type` after the first hop |
| --- | --- | --- | --- | --- |
| CHG005367969 | yes, in the create payload | **+599 s** | no | **`standard`** |
| CHG005368738 | yes, in the create payload | **0 s** | no | `latent` |
| CHG005368783 | no | — | yes, three times (to +2 h) | `latent` |
| CHG005368567 | no | — | yes | `latent` |

Two facts that matter for any fix, both from the history rather than inference:

1. **The flip happens at the first hop, not at create.** On CHG005368738, update #0 (create)
   records `type ""→"Standard"`, and update #2 — the Assess hop — records
   `type "Standard"→"Latent (Violated)"`. So a `type` read straight after create looks fine on a
   change that is already doomed.
2. **Editing the window afterwards does not undo it.** CHG005368783 had its window moved to two
   hours in the future *before* the hop and was still reclassified. A repair PATCH cannot rescue
   a change that was created without a properly scheduled window.

Which condition exactly the rule evaluates cannot be pinned down without writing to the
instance, and the read-only evidence supports more than one reading — «the planned start was in
the future at insert» and «the window was set at insert and never edited» both fit all four
tickets. The only pattern **observed to survive** is CHG005367969's: a future planned start,
present in the create payload, never edited afterwards. That is what this skill does.

### Design consequences

* **File the planned start in the future, inside the create payload.** This skill defaults
  `--lead-time=300` (5 minutes) and warns at `--lead-time=0`. Manual runs that stayed
  `standard` used +10 and +15 minutes.
* **Read `type` back after create *and after every hop*, with `sysparm_display_value=false`.**
  After create it catches an already-latent record; after the Assess hop it catches the flip at
  the moment it happens, which is two steps before the symptom.
* **Do not trust field values on a latent record.** Three create fields are cleared by the same
  update, so «the write did not stick» and «mandatory field not filled in» are *symptoms* on
  such a record, not causes. Check `type` first.
* **A repair of the planned window is a red flag, not a fix.** If the window is missing after
  create, the change is probably already going to be reclassified; the skill repairs the data but
  says so, and the `type` check after the Assess hop is what stops the run.
* **An automatic `Assess → Review` is the loudest available signal.** If a single-hop request
  lands two states along, read `type`.

## Endpoints

| Verb | Path | Purpose |
| --- | --- | --- |
| POST | `/api/now/table/change_request` | create, with the field set below |
| GET | `/api/now/table/change_request/{sys_id}?sysparm_display_value=false` | verified read |
| GET | `/api/now/table/change_request?sysparm_query=number=CHG…` | CHG number → sys_id |
| PATCH | `/api/now/table/change_request/{sys_id}` | `work_notes`, `work_start`/`work_end`, close fields |
| GET | `/api/now/table/change_request?sysparm_query=sys_id={sys_id}&sysparm_fields=work_notes&sysparm_display_value=true` | **the only way to read a journal.** See «Journals» below |
| PATCH | `/api/now/change_request_calendar/change_request/{sys_id}` | the planned window: `{start_date, end_date}` |
| POST/PATCH | `/api/now/table/change_request` | the planned window is **also** accepted here, both on create and as a PATCH |
| PATCH | `/api/sn_chg_rest/change/{sys_id}` | `u_risk_type` — the one field create really does drop (it is derived from service + risk by the `populateRiskType` GlideAjax). `u_environment` does not need this call |
| GET | `/api/sn_chg_rest/change/{sys_id}/nextstates` | which hops are legal, and which conditions fail |

`nextstates` returns `{result: {available_states: ["-4","4","-5"], state_label: {…},
state_transitions: [[{from_state, to_state, display_value, transition_available,
automatic_transition, conditions:[{passed, condition:{name, description}}]}]]}}`.
Check it before every hop and quote the failing condition names when a hop is
unavailable.

## State codes (confirmed live twice: `nextstates`, and the `state` choice list in `/api/now/ui/meta/change_request`)

| Code | Label |
| --- | --- |
| `-5` | New |
| `-4` | Assess |
| `-3` | Authorize |
| `-2` | Scheduled |
| `-1` | Implement |
| `0` | Review |
| `3` | Closed |
| `4` | Canceled |

`state` itself is `read_only: true` in the dictionary, which is why REST cannot move it:
a Table API or `sn_chg_rest` PATCH of `state` returns 200 and changes nothing.

Required fields per hop (the form is the oracle — it names the missing field, REST does not):

| Hop | Needs |
| --- | --- |
| `-5 → -4` New → Assess | the create field set, **plus `u_change_fixing_cso`** and `u_tenant_type`. Without the CSO field the form refuses with «The following mandatory fields are not filled in: Change is related to an emergency» |
| `-4 → -1` Assess → Implement | `u_change_approver`, and `u_tenant_type` if it was left empty |
| `-1 → 0` Implement → Review | `work_start`, `work_end` — **actuals**, so they can only be measured or supplied, never derived from configuration |
| `0 → 3` Review → Closed | `close_code`, `u_impact_minutes`, `close_notes` |

Observed transition graph on the Adobe change model:
`New → {Assess, Canceled}`, `Assess → {New, Authorize, Implement, Review*, Canceled}`
(`Review` is automatic-only), `Implement → {Review, Canceled}`, `Review → {Closed}`.

## Transitions go through the form, not the API

State is guarded by the ADB change model, so a table PATCH of `state` is not
enough. Open the form and submit it:

```
https://adobe.service-now.com/change_request.do?sys_id=<sys_id>&sysparm_view=ADBChangeForm&sysparm_view_forced=true
```

then, in the page:

```js
g_form.setValue('state', '-4');                                  // target code
gsftSubmit(null, g_form.getFormElement(), 'adb_sysverb_update_and_stay');
```

Confirmed present on that page: `g_form` (object), `gsftSubmit` (function),
`g_form.checkMandatory` (boolean), `#output_messages`, and the glide_list DOM
below. `g_form.getUniqueValue()` returns the record's sys_id — use it to prove the
form loaded the record you meant.

Afterwards **poll `GET …/change_request/{sys_id}?sysparm_fields=state`** until the
code matches or a timeout expires. The submit returns before the round trip.

### Cancelling

Cancelling out of Implement trips mandatory-field validation. Disable it first, in
the same eval, before setting the state:

```js
g_form.checkMandatory = false;
g_form.setValue('state', '4');
gsftSubmit(null, g_form.getFormElement(), 'adb_sysverb_update_and_stay');
```

### glide_list fields ignore `g_form.setValue`

`u_service_offering_instance` and `u_change_approver` are `glide_list`. When they
must be set on the form, write all three DOM nodes:

```js
document.getElementById('change_request.<field>').value = '<sys_id>';
document.getElementById('sys_display.change_request.<field>').value = '<display>';
const sel = document.getElementById('select_0change_request.<field>');
const opt = document.createElement('option');
opt.value = '<sys_id>'; opt.text = '<display>'; opt.selected = true; sel.appendChild(opt);
```

All three ids exist on the ADBChangeForm view (verified). Setting these fields in the
**create POST body** works normally, so the DOM dance is only needed when the form itself
validates them: the `Assess → Implement` hop demands `u_change_approver`. The skill reads the hidden input
before that hop and only writes the nodes if the form shows it empty.

This is not hypothetical: on CHG005368567 the record holds
`u_change_approver = c6c2bfff3755df8047afc8cfc3990ed9` (raw read) while the form's hidden
`change_request.u_change_approver` input is **empty**. A value set through the Table API does
not reach that input, so the form-side validation would refuse the hop for a field the record
demonstrably has. `change form <CHG>` reports both, read-only.

## Create field set (as stored on CHG005367969, Closed/Successful)

| Field | Value | Note |
| --- | --- | --- |
| `short_description` | the change title | required |
| `description` | long description | defaults to the wrapped command line |
| `type` | `standard` | **read it back**: a planned start that is not in the future silently turns this into `latent`, which cannot reach Implement (see above) |
| `category` | `Other` | |
| `chg_model` | `74c98c77876939502140b916cebb357c` | Adobe Change Model |
| `cmdb_ci` | `a45e845893ffce50b7aef1e01bba10a1` | EDS Delivery |
| `u_service_offering_instance` | `d0ae449893ffce50b7aef1e01bba1049` | EDS Delivery (AEM Cloud - AWS - Franklin - Prod) |
| `u_hosting_location` | `199c4601870c1990ae3497983cbb3543` | **reference field**, display value `USA1`. Posting the literal label `USA1` also works — the Table API resolves it to this sys_id, which is how CHG005367969 was created. The skill sends the sys_id anyway, because resolution by label is silent when it fails |
| `u_tenant_type` | `Multi` | |
| `u_environment` | `production` | raw value, and it **does** land on create — `sys_history_line` update #0 of CHG005368567 shows `u_environment ""→"production"`. The earlier «silently dropped» note applied to `u_risk_type` only |
| `u_customer_impact` | `none` | raw value; label «No Impact» |
| `u_change_complexity` | `Straight Forward` | |
| `u_cr_reason_justification` | `maintenance` | **raw value.** CHG005367969 wrongly holds the label `Maintenance` — see «Raw values versus labels» below |
| `u_backout_plan_type` | `Roll back` | |
| `u_production_validation_testing_method` | `live_monitoring` | **raw value.** CHG005367969 wrongly holds the label `Live monitoring` |
| `u_change_target_type` | `technical_service` | |
| `u_classification` | `configuration` | |
| `u_documentation` | ITChangeManagement SharePoint URL | |
| `u_risk_type` | `Minor` | **Silently dropped by the create call** — the instance derives it from service + risk via the `populateRiskType` GlideAjax. The `sn_chg_rest` PATCH is what makes it stick |
| `risk` / `impact` / `urgency` / `scope` | `4` / `2` / `2` / `3` | Low / Medium / Medium / Medium |
| `cab_required` | `false` | |
| `u_change_deployer`, `requested_by`, `u_submitter` | `a3b27bff3755df8047afc8cfc3990e7c` | the acting user (trieloff) |
| `u_change_approver` | `c6c2bfff3755df8047afc8cfc3990ed9` | rofe (Raphael Wegmueller) |
| `implementation_plan`, `backout_plan`, `test_plan`, `justification`, `risk_impact_analysis` | prose | all five are validated by the New → Assess condition |

### The planned window, and the endpoint that lies about it

`start_date` / `end_date` stick through `POST /api/now/table/change_request` (create) and
`PATCH /api/now/table/change_request/{sys_id}`, including a window starting exactly «now».
Send them **in the create payload**; that is how CHG005367969 and CHG005367248 got theirs
(`sys_history_line` update #0 on both). The start must be **in the future** — see the latent
section above; the retry-and-repair ladder below exists because a latent record will not hold
these fields, not because a `standard` record needs it.

**Do not use `PATCH /api/now/change_request_calendar/change_request/{sys_id}`.** Despite
taking `{start_date, end_date}`, on this instance it writes **`work_start` / `work_end`** —
the actual work times — and leaves the planned window empty. Proven on CHG005368567: that
call was the only write between create (update #0) and the next read, and update #1 is
`work_start`/`work_end` ← the create-time window, with `start_date`/`end_date` still empty.

Two consequences followed from those stray actuals, both worth knowing because they look like
bugs elsewhere:

* the model **auto-advanced Assess → Review** (that transition is automatic once its
  conditions are met, and populated actuals satisfy them), so a single-hop request appeared to
  overshoot;
* the change was **reclassified `type` Standard → «Latent (Violated)»**, because work had
  apparently started before the planned window.

So actuals must be written once, at the end, and never as a side effect of setting the window.

## `u_change_fixing_cso` — the field that blocks New → Assess

«Change is related to an emergency», a `select-one`. It is **mandatory for the first hop** and
is not in any of the older reference payloads, so a change created without it cannot leave
`New`. Settable over REST, including in the create body.

| raw value | label |
| --- | --- |
| `` | -- None -- (blocks the hop) |
| `No - non-emergency` | No - Non-Emergency |
| `Yes - Fix CSO` | Yes - Fix CSO |
| `Yes - Prevent a CSO` | Yes - Prevent a CSO |

Note the exact spacing and hyphenation, and that the first raw value differs from its label in
**case**. The skill defaults to `No - non-emergency` and exposes `--cso=none|fix|prevent`;
`none` means «not an emergency change», which is a real value, not the empty option.

## Raw values versus labels — a silent data-quality trap

Choice fields store a **raw value**; the form and any read with
`sysparm_display_value=true` show a **label**. The Table API stores whatever string it is
handed, **including a label**, without complaint. Both render identically on screen, so the
damage is invisible in the UI — but any report, filter, metric or business rule keyed on the
raw value silently misses the label-valued record.

Measured on this instance, reading both records with `sysparm_display_value=false` and
`=true`:

| field | UI-created CHG005367859 (canonical) | API-created CHG005367969 | display, both |
| --- | --- | --- | --- |
| `u_cr_reason_justification` | `maintenance` | `Maintenance` | Maintenance |
| `u_production_validation_testing_method` | `live_monitoring` | `Live monitoring` | Live monitoring |
| `u_hosting_location` | `199c4601870c1990ae3497983cbb3543` | `199c4601870c1990ae3497983cbb3543` | USA1 |

So the ticket that «provably closed» carries subtly wrong data in two fields. **Always send
raw values.** Reference fields behave differently and better: a label posted to
`u_hosting_location` is resolved to the right sys_id.

The skill normalises every choice value to its raw form before sending
(`resolveChoice()`): a raw value passes through, a label is mapped, and anything else is
refused with the allowed list rather than written verbatim. `--no-normalise-choices` turns
that off, with a warning, for the case below.

### The three `glide_static_list` fields are multi-valued

`u_cr_reason_justification`, `u_production_validation_testing_method` and `u_environment`
store a **comma-separated list**, and real records use it: `bug_fixes,maintenance`,
`enhancement,maintenance,new_features`. Each element is resolved separately, so
`--reason="bug_fixes,Maintenance"` is sent as `bug_fixes,maintenance`.

### How consistent is the instance? (200-record samples, read-only)

* `u_cr_reason_justification`: raw dominates — `maintenance` 131/200 plus raw combinations,
  against 7 records holding the label `Maintenance` (CHG005367969 among them). Raw is the
  convention here, and the correction is unambiguous.
* `u_production_validation_testing_method`: **the label dominates** — 189/200 hold
  `Live monitoring`, written mostly by `srv_changemgt_int`, i.e. Adobe's own Change
  Management API Integration, with mixed rows like `Live monitoring,functional_testing`
  proving both forms coexist inside one field.

So this field is already inconsistent instance-wide and either choice mismatches part of the
corpus. The skill sends the raw value, because that is what the choice definition says and
what a filter on the choice list will match, but the majority of history does not agree.
That is a question for change management, not something a wrapper can settle;
`--no-normalise-choices --validation="Live monitoring"` is the escape hatch if the local
reports turn out to key on the label.

## Choice value → label map (live)

Raw values for plain choice fields come from `GET /api/now/ui/meta/change_request`, which
returns authoritative `{label, value}` pairs. `u_cr_reason_justification`,
`u_production_validation_testing_method` and `u_environment` are `glide_static_list`
fields and are **not** in that payload; `sys_choice.value` is ACL-hidden for reads on this
account, so each raw value below was confirmed by *querying* for it
(`sys_choice?sysparm_query=name=change_request^element=<f>^value=<guess>` → hit or miss)
and cross-checked against the UI-created record. ServiceNow string queries are
case-insensitive, so that oracle proves the token shape; the case comes from the record.

```
u_customer_impact       none | Low | Medium | High
                        (No Impact | Unnoticeable | Degradation | Outage / Extended Downtime)
u_change_complexity     Straight Forward | Complex | Very Complex          (raw = label)
u_backout_plan_type     Roll back | Roll forward                          (raw = label)
u_tenant_type           Multi | Single                                    (raw = label)
u_risk_type             Minor | Major | Standard                          (raw = label)
u_change_target_type    technical_service | device_group
u_classification        configuration | software | hardware
close_code              successful | successful with issues | unsuccessful
type                    standard | normal | emergency | expedited | latent
                        (Standard | Normal | Emergency | Urgent | Latent (Violated))
category                Other | Hardware | Software | Network | …          (raw = label)
u_cr_reason_justification
    maintenance (Maintenance) · patching · security · bug_fixes (Bug Fixes) ·
    new_features · system_upgrade · enhancement · prevent_service_outage ·
    restore_from_outage · roll_back_partial (Roll Back - partial) · roll_back_complete
u_production_validation_testing_method
    live_monitoring (Live monitoring) · smoke · canary · regression · functional_testing ·
    blue_green (Blue/ Green) · a_b (A/B) · load · manual_steps · business · operational ·
    splunk · pat (PAT - Production Acceptance Testing) ·
    observability_tools (Observability tools (New Relic, AppDynamics, Dynatrace))
u_environment           production | stage | qa | development | training | fix |
                        "model office" | "hot backup"
```

Note that the iPaaS transport is the opposite: its JSON contract takes **labels**
(`customerImpact: "No Impact"`), so values must not be shared between the two paths.

## GlideAjax helpers (`POST /xmlhttp.do`, `sysparm_processor=ADBChangeManagement`)

Observed in a human run of the form; useful for understanding why plain writes are dropped:

* `populateRiskType` — `sysparm_ts=<technical service sys_id>&sysparm_risk=4` → `Minor`.
  `u_risk_type` is **derived** from service + risk, which is why a create-time write is
  ignored.
* `checkSOX` — `sysparm_sysid=<service sys_id>` → `false` for EDS Delivery.
* `validateUserAsApprover` — `sysparm_coord=<user>&sysparm_chgId=<chg>` → `true`.
* `validateChangeReview` — `sysparm_chgId=<chg>` → `false` while Review preconditions
  are unmet.

Each returns `<xml answer="…"/>`. The skill does not call them: it uses `nextstates` plus
the form's own banner, which covers the same ground with fewer moving parts.

## Journals are readable only through a display read of the parent record

`work_notes` (and any journal field) cannot be read the obvious ways on this instance:

* a **raw** read — `sysparm_display_value=false` — returns an empty string, because journal
  fields only materialise on a display read;
* `sys_journal_field` is **ACL-blocked** for this account: a query by `element_id` returns
  `[]`, with HTTP 200 and no error, which looks exactly like «the note was not written».

The one call that works:

```
GET /api/now/table/change_request?sysparm_query=sys_id=<sys_id>
    &sysparm_fields=work_notes&sysparm_display_value=true
```

It returns the whole journal, newest entry first, each entry prefixed
`MM-DD-YYYY HH:MM:SS - <user> (Work notes)`. Verify a write by substring-matching something
distinctive you just posted — this skill matches the rendered command line — rather than by
comparing the whole value.

This cost a false «could not be verified» warning on an otherwise perfect run: the note was
there, the verification was looking in two places that cannot answer.

## Dates

* Internal format, UTC: `YYYY-MM-DD HH:MM:SS` — used for `start_date`, `end_date`
  and (first attempt) `work_start` / `work_end`.
* Display format for this account: `MM-DD-YYYY HH:MM:SS`, sent with
  `sysparm_input_display_value=true`. Used as the fallback for `work_start` /
  `work_end`, which is the form that was proven to stick on CHG005367969.
* The recorded actual window is floored to 60 seconds: a command that finishes within a second
  would otherwise store `work_start == work_end`, which reads like a defect and gives reports a
  zero-length window. The true measured duration stays in the work notes.
* Display-format writes are interpreted in the **user profile's** timezone. This
  skill renders UTC clock values into that format, so if the profile is not UTC the
  actual-work timestamps land off by the offset. Verified read-back is printed, so
  a mismatch is visible.

## Approvals

Do not touch them. Approvals are workflow-only: writes to `sysapproval_approver`
get their references stripped and the rows cannot be deleted. `u_change_approver`
on the change itself is the only approver field this skill sets. On the proven
record `approval=not requested`, `u_approval_obtained=false`,
`u_approval_step=Not Requested`, and it still closed Successful.
