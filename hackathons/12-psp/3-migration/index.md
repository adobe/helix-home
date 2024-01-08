# Backwards compatibility within reason, Migration is a reality


## Migration plan
- Ideal state: magic. Customer doesn't need to do much
    - If v5 config is empty, get v4 config, write to v5
    - Debate: Admin panel to migrate v4 config to v5?

## Long-term backwards compatibility perspective
- Assumption:
    - Copy of customer site is created during "migration"
    - If config migration is "Magic" (e.g. empty v5 config is populated from known v4 locations), how much would need to be backwards compat?
    - Some level of manual work is expected (e.g. CDN switch, any secrets that might get put into Custom config).

## Migration / rollout plans
- Customer communication
    - Need to determine "value propositions" (e.g. RSO, admin area, config bus) that can/should be communicated
