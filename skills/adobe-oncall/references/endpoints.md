# Adobe On-Call — API Endpoints

**Instance:** `adobe.service-now.com`
**App path:** `/x/adosy/on-call/home`
**Auth:** Session-based (Okta SSO + `X-UserToken` / `g_ck` CSRF header)
**Access method:** XHR from ServiceNow workspace page context

## Table API

### Incident Table: `x_adosy_adb_on_ca_incident`

Standard ServiceNow Table API. Prefix: OCINC.

```
GET /api/now/table/x_adosy_adb_on_ca_incident
    ?sysparm_query=<encoded_query>
    &sysparm_fields=<comma_separated>
    &sysparm_limit=<n>
    &sysparm_display_value=true
```

**Key fields:**
- `number` — OCINC prefix (e.g., OCINC2145403)
- `short_description` — incident summary
- `state` — 1=Open, 2=Work in Progress, 3=Resolved, 4=Closed, 60=Re-Open
- `priority` — severity level
- `assigned_to` — person handling the incident
- `assignment_group` — on-call group
- `opened_at` — when the incident was created
- `u_acknowledged` — whether incident has been acknowledged
- `u_acknowledged_by` — who acknowledged it
- `active` — boolean, whether incident is still active

**Common queries:**
- Active incidents for my groups: `assignment_groupDYNAMICd6435e965f510100a9ad2572f2b47744^active=true^stateIN1,2,60`
- The DYNAMIC value `d6435e965f510100a9ad2572f2b47744` resolves to "One of My Groups"

### Major Incident Table: `x_adosy_mi_major_incident`

Same API pattern, different table for severity 1/critical incidents.

### PATCH (update incident)

```
PATCH /api/now/table/x_adosy_adb_on_ca_incident/<sys_id>
Content-Type: application/json

{"state": "2", "u_acknowledged": "true", "assigned_to": "<user_sys_id>"}
```

## UX Framework Databroker

### Schedule / On-Call Summary

```
POST /api/now/uxf/databroker/exec
Content-Type: application/json

[{
  "type": "GRAPHQL",
  "definitionSysId": "1a7dd83d1b31b114fde1c8451a4bcba3",
  "inputValues": {
    "groupSysId": {"type": "JSON_LITERAL", "value": null},
    "userSysId": {"type": "JSON_LITERAL", "value": null}
  },
  "pipelineId": "get_on_call_summary_info"
}]
```

**Response structure:**
```json
{
  "result": [{
    "executionResult": {
      "output": {
        "data": {
          "xAdosyAdbOnCa": {
            "adbOnCall": {
              "getSummaryCardInfo": {
                "userIsOnCall": false,
                "usersCurrentShifts": null,
                "usersFutureShifts": [
                  {
                    "userName": "...",
                    "userId": "...",
                    "startDate": "2026-05-17 05:00:00",
                    "endDate": "2026-05-17 17:00:00",
                    "roster": "EMEA",
                    "groupName": "AEM - Helix v2",
                    "groupSysId": "...",
                    "type": "roster"
                  }
                ],
                "hasActiveRota": true,
                "whoIsOnCall": []
              }
            }
          }
        }
      }
    }
  }]
}
```

## Rotation Tables (standard ServiceNow)

- `cmn_rota` — rotation definitions (name, group, schedule)
- `cmn_rota_member` — members in a rotation (with order)
- `cmn_rota_roster` — roster within a rotation (e.g., "EMEA", "NA")
- `roster_schedule_span` — computed schedule spans per roster

## Related: Alerts

On-call incidents can have related alerts. Accessed via databroker composite queries with the incident sys_id.
