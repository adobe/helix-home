# Backwards compatibility within reason, Migration is a reality


## Migration plan
- Ideal state: magic. Fewest manual steps as possible 
    - If v5 config is empty, get v4 config, write to v5
    - Debate: Admin panel to migrate v4 config to v5? Seems like this shouldn't be a thing.

## Long-term backwards compatibility perspective
- Assumption:
    - type in aem.live and the site loads correctly 
    - If config migration is "Magic" (e.g. empty v5 config is populated from known v4 locations), how much would need to be backwards compat?
    - need visual editor for configs non-migrated or manual configurations
    - Some level of manual work is expected (e.g. CDN switch, any secrets that might get put into Custom config).

## Migration / rollout plans
- Customer communication
    - Need to determine "value propositions" (e.g. RSO, admin area, config bus) that can/should be communicated
    - Need a pre-golive checklist (likely similar to current go-live checklist) for v5.
 
## Open questions
- Who gets v5, who doesn't? What timeline? 
    - Customers whos see no tangible benefit (aside from Adobe, supporting old arch)
    - Customers who are awaiting future features of v5 (e.g. Pfizer)
    - In between?
    - Everyone?? Pre-populate config bus with v4 configs... 
