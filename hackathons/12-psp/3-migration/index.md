# Backwards compatibility within reason, Migration is a reality


## Migration plan
- Ideal state: magic. Fewest manual steps as possible 
    - If v5 config is empty, get v4 config, write to v5 config bus
    - Debate: Admin panel to migrate v4 config to v5? Seems like this shouldn't be a thing.

## Long-term backwards compatibility perspective
- Assumption: Not much (if any) is necessary
    - type in aem.live and the site loads correctly 
    - If config migration is "Magic" (e.g. empty v5 config is populated from known v4 locations), how much would need to be backwards compatible?
    - Visual editor for non-migrated or manual configurations??
    - Some level of manual work is expected (e.g. CDN/DNS switch, any secrets that might get put into Custom config).

 
## Customer prioritization
- Who do we prioritize to make the switch? Do we approach all or leave some alone? What timeline?
- Customer groups:
    1. Customers who have requested and are awaiting these future features (e.g. Pfizer)
    2. Customers who are actively expanding their usage and may take advantage of new functionality in their plans once aware
    3. Customers who are not actively expanding their sites/functionality and will see no tangible benefit (e.g. BASF, potatoes, etc)
    4. Customers who we manage DNS (77)
- **Proposal:**  from an ease of maintenance perspective, let's run this for everyone. i.e Pre-populate config bus with v4 configs so all are ready to make the switch once we communicate that they need to and help them to do it, regardless of their customer group.  Avoid maintaining/supporting both for any longer than necessary.  


## Customer communication
- Language is important! Referring to this as a new "version" will prompt customers to think in terms of upgrade considerations and planning (and past headaches).  Instead, suggest we talk about this just in terms of continuous improvements that are based on proven customer needs and usage. "Because of the specifics of our latest feature additions, configuration changes will be required for all on the customer side". Full stop. 
- Need to determine brief "value propositions" (e.g. RSO, admin area, config bus) that can/should be communicated to explain the improvements
- Need a pre-golive checklist (likely similar to current go-live checklist) to provide for validation before switch is committed
- "a centralized and easy-to-use Franklin configuration management utility is being introduced.  to leverage this fantastic tool, a change to your DNS from hlx.live to aem.liv will be required.  On xx/xx/xxxx, the older decentralized configuation schema will be sunset."

---

## Discussion points
- config migration tool (the magic) needs to be built
    - ORG? What is it, where is it, how do we know what it is?
    - what gets migrated, specifically
    - what can't be migrated, what manual steps will be needed regardless 
    - do we run it on every current customer initially? Do we need to tell them?
- Keeping track on customers who have been (and not been) migrated: some reporting will be needed

Draft Migration flow
![Draft Flow](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/draftMigrationFlow.png)

