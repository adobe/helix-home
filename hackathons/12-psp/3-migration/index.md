# Backwards compatibility within reason, Migration is a reality

[Demo](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#demo) | 
[Customer prioritization](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#customer-prioritization) | 
[Customer communication considerations](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#customer-communication) | 
[Migration Scenario A](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#scenario-a) | 
[Migration Scenario B](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#scenario-b) | 
[Discussion Points](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#discussion-points)

## Demo
Track focus
- Migration plan
- Long-term backwards compatibility

### Overall goals
- Support v4 arch for the least time possible, get everyone on v5 as quickly as is reasonable 
- Lowest friction possible, as much “magic” as possible
- Nothing breaks, everything just works, configurations fallback to v4 

#### Backwards compatibility
- Our initial thought was that there wouldn’t be a need for [any](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#scenario-a), just migrate as much as possible
- As it turns, it just works

#### [Customer bucket(s)](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#customer-prioritization)
1. Lower touch / “Informed”: Customers in we have control over their DNS/CDN entry, lower page views in RUM
2. Higher touch / “Guided” / White Glove: Customers who will be early adopters or who are complex (e.g. Pfizer, a.com)
3. Customers who need to be “Convinced” 

#### Migration plan
- WIP: put customers in buckets
    - Get customer contact for DNS/CDN
        - This will be a a little different based on customer bucket 
    - Switch customer to aem.live 
        - Net new projects start on aem.live
    - Once customers are all using aem.live, likely can remove hlx.live
  
#### Awaiting
- Migration of configurations from v4 architecture to v5 config bus
- Admin UI
- Documentation
- Redirect mechanism from hlx.page to aem.live, so user browsing experience is seamless (browser history is how you get to your content

  
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)

---

## Customer prioritization
- Who do we prioritize to make the switch? Do we approach all or leave some alone? What timeline?
- Possible Customer groupings:
    1. Customers who have requested and are awaiting these future features (e.g. Pfizer)
    2. Customers who are actively expanding their usage and may take advantage of new functionality in their plans once aware
    3. Customers who are not actively expanding their sites/functionality and will see no tangible benefit (e.g. BASF, potatoes, etc)
    4. Customers who we manage DNS (77)
- **Proposal:**  Run both architectures for the least amount of time. While there isn't significant technical cost for running both architectures concurrently, from an ease of maintenance perspective it should be our goal to push customers quickly to v5. If it is possible to run the congfig migration for all (or most) currently live customers and automate ORG creation. Specifically, pre-populate config bus with v4 configs so all customers are ready to make the switch (once we communicate that they need to, and help them to do it) regardless of their customer group.  Doing so will avoid maintaining/supporting both architectures for the least amount of time.  

  
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)


## Customer communication
- ORG: Need to identify qualified person / email for org, need some comms for this 
- Language is important! Referring to this as a new "version" will prompt customers to think in terms of upgrade considerations and planning (and past headaches).  Instead, suggest we talk about this just in terms of continuous improvements that are based on proven customer needs and usage. "Because of the specifics of our latest feature additions, configuration changes will be required for all on the customer side". Full stop. 
- Need to determine brief "value propositions" (e.g. RSO, admin area, config bus) that can/should be communicated to explain the improvements
- Need a pre-golive checklist (likely similar to current go-live checklist) to provide for validation before switch is committed
- "a centralized and easy-to-use Franklin configuration management utility is being introduced.  to leverage this fantastic tool, a change to your DNS from hlx.live to aem.liv will be required.  On xx/xx/xxxx, the older decentralized configuation schema will be sunset."
  
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)

---


## Scenario A: 
Magic (Do it, inform, ask for validation) -> Auto creation of org (if possible), auto migration of v4 Configurations to v5 Configbus
### Migration plan
 
 - Ideal state: Fewest manual steps as possible. 
    - script run per customer, org created
    - If v5 config is empty, get v4 config, write to v5 config bus
    - Customer is informed of v5 changes, how configs will work going forward, delivered a golive checklist of testing and manual steps (e.g. change DNS/CDN)
    - Site reviewed and tested by customer/Adobe, any configs missed in migration are added to config bus
    - Customer/Adobe makes necessary changes and is now on v5.  Pull request to remove v4 migrated items (created originally via migration tool)
    - Assumes:
       - org (name, owner, email etc.) can be created via magic, owner identified.
          - Can this be intuited via the bot? Who installed it, what org it belongs to?
          - Org name collisions? Solved via github org name, otherwise this needs to be solved for
      - There is no issue with just "doing it"
      - Some level of white-glove will likely be necessary
      - No need to copy content or code for migration purposes

#### Long-term backwards compatibility perspective
- Assumption: Not much (if any) is necessary if configuration migration and org creation can be Magic 
    - type in aem.live and the site loads correctly 
    - Visual editor for non-migrated or manual configurations via admin utility
    - Some level of manual work is expected (e.g. CDN/DNS switch, any secrets that might get put into Custom config).

   
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)

---


## Scenario B: 
Backwards compatible (inform, get information and permission, do it, ask for validation) -> v4 Configurations via fallback, manual (or semi-automated via button) migration to v5 config

### Migration plan
 
- Ideal state: 
    - Customer info (org, owner, email etc) is retrieved, likely via customer interaction
    - Org is created and populated with above detail
    - Customer is provided .aem.live url, can see site works. 
    - Customer is provided admin panel to migrate (maybe via button) v4 to v5 configs
    - Customer is informed of v5 changes, how configs will work goign forward, delivered a golive checklist of testing and manual steps (e.g. change DNS/CDN)
    - Customer is provided (or creates) PR to remove v4 configs
    - Assumes:
      - Some level of indicator is provided (admin panel) of what configs are being used prior to switch-over and v4 config deletion
      - No need to copy content or code for migration purposes
    - Considerations:
      - What happens if a customer is resistant (slow to respond, uninterested, etc)

  
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)

---

## Discussion points
- For free-tier customers, some level of alerting will be necessary, likely via sidekick notification
- config migration tool (the magic) needs to be built
    - ORG? Identifying the person / email of the owner. What is it, where is it, how do we know what it is?
    - what gets migrated, specifically.  List should likely be inclusive of all config items listed in [v5 arch](https://www.aem.live/drafts/uncled/helix5#config-service-aspects)
    - what can't be migrated, what manual steps will be needed regardless 
    - do we run it on every current customer initially? (yes, but prioritizing order based on customer groups as defined?)  What would it take to automate as much of this as possible, including ORG considerations
- Keeping track of customers who have been (and not been) migrated: some reporting will be needed
- Configbus documentation -> step-by-step of what is different and how to effect changes for newly migrated customers
  
^[Back to top](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/index.md#backwards-compatibility-within-reason-migration-is-a-reality)


Draft Migration flow
![Draft Flow](https://github.com/adobe/helix-home/blob/main/hackathons/12-psp/3-migration/draftMigrationFlow.png)

