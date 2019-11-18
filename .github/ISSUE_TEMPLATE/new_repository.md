---
name: New Repository
about: Checklist for the creation of a new repository
title: ''
assignees: ''

---

Create a new repository with the name `helix-…`

## Stuff you can do yourself

- [ ] Use [`helix-library`](https://github.com/adobe/helix-library) or [`helix-service`](https://github.com/adobe/helix-service) template
- [ ] Add topics to the repository, at least `helix`
- [ ] Add the group "[Project Helix Admins](https://github.com/orgs/adobe/teams/project-helix-admins)" with *Admin* permissions to the list of collaborators
- [ ] Add the group "[Project Helix Developers](https://github.com/orgs/adobe/teams/project-helix-developers)" with *Write* permissions to the list of collaborators (Project Helix Guests will be taken care of automatically) 
- [ ] Upload a social media image (use [this Spark template](https://spark.adobe.com/post/7srrIXaQVTw67/))
- [ ] Set the repository description
- [ ] Update the repository `README.md` (search for "service" and replace with something more meaningful where appropriate)
- [ ] Update the `package.json` (search for "service" and replace with something more meaningful where appropriate)
- [ ] Uncomment and adjust the `helix-post-deploy/monitoring` command in `.circleci/config.yaml`: decide if your service falls under Development, Publishing or Delivery. (note: failures in the latter two categories will ping on-call engineers on weekends!) See https://status.project-helix.io for reference.
- [ ] Set up **Project Bot**
  - [ ] Add a [`.github/org-project-bot.yaml`](https://github.com/adobe/helix-cli/blob/master/.github/org-project-bot.yaml)
- [ ] Set up [CircleCI](https://circleci.com/add-projects/gh/adobe)
- [ ] Follow the project in Slack channel [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/)
> Open the [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/) Slack channel, then type `/github subscribe adobe/helix-…`

## Stuff you need an Adobe Org Admin for
- [ ] Enable [OrgProjectBot](https://github.com/organizations/adobe/settings/installations/690408)
- [ ] Enable [Renovatebot](https://github.com/organizations/adobe/settings/installations/1325372)
- [ ] Set up [Commitlint](https://github.com/organizations/adobe/settings/installations/728398)


