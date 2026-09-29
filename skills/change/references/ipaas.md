# iPaaS transport contract (`--via=ipaas`)

Adobe's headless Change Management API, reverse-engineered from
`adobe/semantic-release-ipaas-chg`. **Unproven here: no credentials exist**, so
nothing in this file has been executed against the live gateway by this skill.
`--via=servicenow` needs no secrets and is the default for that reason.

## Hosts

| env | iPaaS host | IMS host |
| --- | --- | --- |
| `prod` | `ipaasapi.adobe-services.com` | `ims-na1.adobelogin.com` |
| `stage` | `ipaasapi-stage.adobe-services.com` | `ims-na1-stg1.adobelogin.com` |
| `dev` | `ipaasapi-dev.adobe-services.com` | `ims-na1-stg1.adobelogin.com` (guess — the dev IMS host was never documented) |

## Headers

Every request carries two:

```
Authorization: <IMS access token>      # NO "Bearer " prefix — the reference client sends the raw token
api_key: <IPAAS_API_KEY>
```

`--bearer` adds the `Bearer ` prefix for gateways that want it.

## IMS token

```
POST https://<ims host>/ims/token
Content-Type: application/x-www-form-urlencoded

client_id=…&client_secret=…&grant_type=authorization_code&code=…
→ { "access_token": "…" }
```

`grant_type=authorization_code` with a long-lived `code` is unusual; whether that
code expires is an open question. Failures must be reported by `error` /
`error_description` only — never echo the response body, it can contain what was
sent.

## Calls

| Purpose | Call |
| --- | --- |
| create | `POST /change_management/changes` with `{title, description, coordinator, customerImpact, approvedBy:[ldap…], testPlan, implementationPlan, backoutPlan, serviceId (number), instances:[{environment, hostingLocation}], plannedStartDate (unix s), plannedEndDate (unix s)}` → `{id}` — a **transaction** id, not a CHG number |
| poll | `GET /change_management/transactions/{txId}` → `{result:{id, status:"Success"\|"Failed", changeId, error}}` — poll until Success/Failed, bounded (this skill: 5 s interval, 180 s cap) |
| read | `GET /change_management/changes?property=changeId==CHG0123456` → `{result:{data:[…]}}` |
| annotate | `POST /change_management/changes` with `{id, notes}` (+ optional `actualStartDate`, `actualEndDate`, unix seconds) |
| close | `POST /change_management/changes` with `{id, state:"Closed", closeCode:"Successful"}` |
| cancel | `POST /change_management/changes` with `{id, state:"Cancelled"}` |

Anything that is not `Success`/`Failed`/`Error` counts as still pending: the
pending status string was never observed.

## Credentials

Resolution order per value: explicit flag → skill config (`<skill>/.config`) →
environment variable. Secrets belong in the environment, not in the config file;
this skill never writes them to disk and never prints them.

| Value | Flag | Config key | Env |
| --- | --- | --- | --- |
| API key | `--api-key=` | `ipaasApiKey` | `IPAAS_API_KEY` |
| IMS client id | `--ims-client-id=` | `imsClientId` | `IPAAS_IMS_CLIENT_ID` |
| IMS client secret | `--ims-client-secret=` | — | `IPAAS_IMS_CLIENT_SECRET` |
| IMS code | `--ims-code=` | — | `IPAAS_IMS_CODE` |
| environment | `--ipaas-env=` | `ipaasEnv` | `IPAAS_ENV` |

When any of the four is missing, `change --via=ipaas` refuses before doing anything
and names exactly which ones are absent.

## Known blocker: the secret firewall

`oauth-token adobe` yields a valid IMS **user** token, but SLICC's secret firewall
refuses to send it to the gateway:

```
Secret oauth.adobe.token is not allowed for domain ipaasapi.adobe-services.com
```

and the gateway additionally requires an `api_key`, which the OAuth flow does not
produce. Widening the secret's scope to `*.adobe-services.com` plus an API key
would be needed before that route can work at all.

## Field mapping used by this skill

`coordinator` ← `--deployer`, `approvedBy` ← `[--approver]`, `serviceId` ←
`Number(--ci)`, `instances` ← `[{environment: --environment, hostingLocation:
--hosting-location}]`, `customerImpact` ← `No Impact`. Note the mismatch: the
ServiceNow path takes sys_ids and the iPaaS path expects an LDAP id and a numeric
service id (`553129` for EDS Delivery, per the earlier recon), so `--ci`,
`--approver` and `--deployer` must be given in iPaaS terms when `--via=ipaas` is
used. This asymmetry is unresolved and untested.
