---
name: New Repository
about: Checklist for the creation of a new repository
title: ''
assignees: ''

---

Create a new repository with the name `helix-…`

- [ ] Use [`helix-library`](https://github.com/adobe/helix-library) or [`helix-service`](https://github.com/adobe/helix-service) template
- [ ] Add topics to the repository, at least `helix`
- [ ] Add the group "[Project Helix Admins](https://github.com/orgs/adobe/teams/project-helix-admins)" with *Admin* permissions to the list of collaborators
- [ ] Add the group "[Project Helix Developers](https://github.com/orgs/adobe/teams/project-helix-developers)" with *Write* permissions to the list of collaborators (Project Helix Guests will be taken care of automatically) 
- [ ] Upload a social media image (use [this Spark template](https://spark.adobe.com/post/7srrIXaQVTw67/))
- [ ] Set the repository description
- [ ] Update the repository `README.md` (search for `adobe/helix-service` or `adobe/helix-library`)
- [ ] Update the `statuspage` and `newrelic` config in `package.json`: decide if your service falls under Development, Publishing or Delivery. See https://status.project-helix.io for reference.
- [ ] Set up [Project Bot]
  - [ ] Add a [`.github/org-project-bot.yaml`](https://github.com/adobe/helix-cli/blob/master/.github/org-project-bot.yaml)
  - [ ] Enable _OrgProjectBot_ under _Settings > Integrations & services or https://github.com/organizations/adobe/settings/installations/690408 (Adobe org admins only)
- [ ] Set up [CircleCI](https://circleci.com/add-projects/gh/adobe)
- [ ] Set up Greenkeeper 
> *Note:* Greenkeeper will find your new repository automatically, but it might file an issue that it cannot get the build status if you haven't set up CircleCI yet. In this case, go to [Greenkeeper](https://account.greenkeeper.io/account/adobe) and click the "fix repo" button
- [ ] Set up [LGTM](https://github.com/organizations/adobe/settings/installations/870657)
- [ ] Set up [Commitlint](https://github.com/organizations/adobe/settings/installations/728398)
- [ ] Follow the project in Slack channel [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/)
> Open the [`#helix-noisy`](https://cq-dev.slack.com/archives/C9HH8J553/) Slack channel, then type `/github subscribe adobe/helix-…`
- [ ] Set up [Snyk](https://app.snyk.io/org/adobe-org/sources/9a3e5d90-b782-468a-a042-9a2073736f0b/add)
