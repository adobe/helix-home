---
name: New Repository
about: Checklist for the creation of a new repository
title: ''
assignees: ''

---

Create a new service|library repository with the name `aem-…`

## Stuff you can do yourself

- [ ] run `npm init @adobe/franklin-library` or `npm init @adobe/franklin-service`
- [ ] Add topics to the repository, at least `helix`
- [ ] Add the group "[Project Helix Admins](https://github.com/orgs/adobe/teams/project-helix-admins)" with *Admin* permissions to the list of collaborators
- [ ] Add the group "[Project Helix Developers](https://github.com/orgs/adobe/teams/project-helix-developers)" with *Write* permissions to the list of collaborators (Project Helix Guests will be taken care of automatically) 
- [ ] Upload a social media image (use [this Spark template](https://spark.adobe.com/post/7srrIXaQVTw67/))
- [ ] Set the repository description
- [ ] List your repository in [AWS IAM](https://us-east-1.console.aws.amazon.com/iam/home?region=us-east-1#/roles/details/helix-gh-deploy-config-service?section=trust_relationships)

### for services
- [ ] Uncomment and adjust the `helix-post-deploy/monitoring` command in `.github/workflows/main.yaml`: decide if your service falls under Development, Publishing or Delivery. (note: failures in the latter two categories will ping on-call engineers on weekends!) See https://www.aemstatus.net for reference.


## Stuff you need an Adobe Org Admin for
- [ ] Enable [Renovatebot](https://github.com/organizations/adobe/settings/installations/1325372)

### for libraries
- [ ] Share the [`ADOBE_BOT_NPM_TOKEN` org secret](https://github.com/organizations/adobe/settings/secrets/actions/ADOBE_BOT_NPM_TOKEN)

