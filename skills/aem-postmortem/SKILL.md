---
name: aem-postmortem
description: >-
  Use this when writing postmortems, incident reports, RCA, root cause analysis,
  post-incident review, or incident documentation for AEM Edge Delivery Services.
  Covers the aem-status repo workflow: creating postmortem files, frontmatter spec,
  impact classification, timeline formatting, PR submission, and monday stand-up
  summaries. Triggers on: postmortem, incident report, RCA, root cause analysis,
  post-incident review, aem-status, incident documentation, AEM incident.
allowed-tools: bash
---

# AEM Postmortem

Write and manage incident post-mortems for AEM Edge Delivery Services, stored in the `aem-status` repo at `/workspace/aem-status/`.

## Quick start

```bash
# Create a new postmortem from the short template
postmortem new --title="CDN Latency Spike"

# Create with the long template (for major/critical)
postmortem new --title="Global Publishing Outage" --template=long

# List recent incidents
postmortem list --limit=5

# Read a specific postmortem
postmortem get AEM-t58nxd8r

# Classify an incident (suggest auto-classification fields)
postmortem classify AEM-t58nxd8r

# Calculate impact level from error rate
postmortem impact 0.034

# Show git/PR commands for submission
postmortem branch AEM-t58nxd8r

# Monday stand-up summary
postmortem monday --range=7d
```

## Postmortem workflow

1. **Create** — `postmortem new` generates an incident file from a template
2. **Gather data** — Use `klickhaus` for CDN log queries, `servicenow` / `oncall` for incident tickets
3. **Write** — Fill in the template sections (see Templates below)
4. **Classify** — `postmortem classify` suggests the auto-classification fields
5. **Submit** — `postmortem branch` shows the PR workflow

## Frontmatter spec

Every postmortem file starts with YAML frontmatter:

```yaml
---
kind: postmortem
impact: none|minor|major|critical
start-time: "2024-01-15T14:30:00Z"
end-time: "2024-01-15T16:45:00Z"
error-rate: 0.0034
impacted-service: delivery|publishing
postmortem-completed: "2024-01-16T10:00:00Z"
---
```

Rules:
- **error-rate**: always decimal — `0.0034` means 0.34%. Compute as `errors / total_log_entries`.
- **impact**: derived from error-rate (see Impact classification below). Never set manually.
- **impacted-service**: only `delivery` or `publishing`.
- **duration**: compute from start-time and end-time. Never eyeball it.
- **timestamps**: ISO 8601 UTC. Be precise.

## Impact classification

| Error rate        | Impact     |
|-------------------|------------|
| < 0.5% (< 0.005) | `none`     |
| < 5% (< 0.05)    | `minor`    |
| < 10% (< 0.1)    | `major`    |
| >= 10% (>= 0.1)  | `critical` |

Use `postmortem impact <rate>` to compute. The rate can be a decimal (0.034) or percentage (3.4%).

## Templates

### Short template (impact: none or minor)

Sections: Executive Summary, Root Cause, Resolution, Action Items, Updates.

Use for incidents with minimal customer impact. Concise — typically 1-2 paragraphs per section.

### Long template (impact: major or critical)

Sections: Executive Summary, Incident Timeline, Impact Analysis, Root Cause Analysis, Trigger, Resolution, Detection, What Went Well, What Could Have Gone Better, Lessons Learned, Action Items (with sub-categories), Updates.

Use for significant incidents requiring thorough post-incident review.

## Updates / Timeline section

- Reverse-chronological order
- Only include entries that actually happened — never fabricate timestamps
- Valid states: `Resolved`, `Monitoring`, `Identified`, `Investigating`
- Post-facto investigations are valid: `Investigating` may have a timestamp after `Resolved`

Format:
```markdown
## Updates

### Resolved
2024-01-15T16:45:00.000Z

This incident has been resolved.

### Monitoring
2024-01-15T16:30:00.000Z

A fix has been deployed. We are monitoring the situation.

### Identified
2024-01-15T15:15:00.000Z

We have identified the root cause and are working on a fix.

### Investigating
2024-01-15T14:35:00.000Z

We are investigating elevated error rates on delivery.
```

## Auto-classification fields

When a PR is opened, a bot classifies the incident in `incidents/index.json`. Know the valid values to pre-validate:

| Field                | Valid values |
|----------------------|-------------|
| `affectedComponents` | `delivery`, `publishing`, or `null` |
| `internalServices`   | `admin-api`, `forms`, `code-sync`, `rum`, `indexing`, `logging`, `dns`, `sidekick`, `media`, or `null` |
| `externalVendors`    | `cloudflare`, `aws`, `fastly`, `github`, `microsoft`, `unpkg`, `zscaler`, `webpack`, or `null` |
| `rootCause`          | `third-party-outage`, `configuration-change`, `deployment-issue`, `resource-limits`, `credential-issue`, `dns-issue`, `network-issue`, `dependency-issue`, `unknown` |

## Git / PR workflow

- **Branch name**: incident ID in all lower case (e.g. `aem-t58nxd8r`)
- **Commit message**: `feat: <incident-summary>`
- **PR description**:
  ```
  Postmortem for #<incident-id>

  URL: https://<branch-name>--aem-status--adobe.aem.page/details.html?incident=<incident-id>
  ```

Use `postmortem branch <id>` to generate the exact commands.

## Gathering data

- **CDN logs / error rates**: Use the `klickhaus` skill to query ClickHouse for delivery and publishing error rates, status code distributions, and affected hosts during the incident window.
- **On-call incidents**: Use the `servicenow` skill to look up related INC tickets, comments, and timeline from the on-call response.
- **Screenshots**: If data is in a screenshot from Klickhaus or Coralogix, extract timestamps, error rates, and affected services from the image.

## Writing guidelines

- Use UTC timestamps throughout
- Be specific about customer impact: number of affected customers, geographic scope, duration
- Distinguish between root cause (systemic) and trigger (immediate precipitating event)
- Action items should be concrete and preventive, not vague
- Link to third-party incident pages when applicable (e.g., Cloudflare status page)
- The executive summary should be understandable by non-technical stakeholders

## Don't

- Don't fabricate timestamps or metrics — ask for the data
- Don't set impact manually — derive it from error-rate
- Don't use `impacted-service` values other than `delivery` or `publishing`
- Don't include timeline entries for events that didn't happen
- Don't forget to compute duration from timestamps
