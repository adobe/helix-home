---
name: New Repository
about: Checklist for the creation of a new repository
title: ''
assignees: ''

---

Create a new repository with the name `helix-…`

## Stuff you can do yourself

- [ ] run `npm init @adobe/helix-library` or `npm init @adobe/helix-service`
- [ ] Add topics to the repository, at least `helix`
- [ ] Add the group "[Project Helix Admins](https://github.com/orgs/adobe/teams/project-helix-admins)" with *Admin* permissions to the list of collaborators
- [ ] Add the group "[Project Helix Developers](https://github.com/orgs/adobe/teams/project-helix-developers)" with *Write* permissions to the list of collaborators (Project Helix Guests will be taken care of automatically) 
- [ ] Upload a social media image (use [this Spark template](https://spark.adobe.com/post/7srrIXaQVTw67/))
- [ ] Set the repository description
- [ ] Uncomment and adjust the `helix-post-deploy/monitoring` command in `.circleci/config.yaml`: decide if your service falls under Development, Publishing or Delivery. (note: failures in the latter two categories will ping on-call engineers on weekends!) See https://status.project-helix.io for reference. (*skip for libraries*)
- [ ] Set up [CircleCI](https://circleci.com/add-projects/gh/adobe)
- [ ] Follow the project in Slack channel [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/)
> Open the [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/) Slack channel, then type `/github subscribe adobe/helix-…`

## Stuff you need an Adobe Org Admin for
- [ ] Enable [OrgProjectBot](https://github.com/organizations/adobe/settings/installations/690408)
- [ ] Enable [Renovatebot](https://github.com/organizations/adobe/settings/installations/1325372)
- [ ] Share the [`ADOBE_BOT_NPM_TOKEN` org secret](https://github.com/organizations/adobe/settings/secrets/actions/ADOBE_BOT_NPM_TOKEN)

