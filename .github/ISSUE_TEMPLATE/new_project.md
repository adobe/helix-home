---
name: New Helix Customer Project
about: Checklist for the creation of a new customer project
title: ''
assignees: ''

---

## Start a new Helix customer project

### Set up the site

- [ ] 1. Create a new GitHub repository using the [adobe/helix-project-boilerplate](https://github.com/adobe/helix-project-boilerplate) repository as template, either under [Helix Sites](https://github.com/organizations/hlxsites/repositories/new) or the customer's own GitHub organization
- [ ] 2. Create a content folder, either in the dedicated SharePoint site for [Helix Projects](https://adobe.sharepoint.com/sites/HelixProjects/Shared%20Documents/Forms/AllItems.aspx?id=%2Fsites%2FHelixProjects%2FShared%20Documents%2Fsites) or in the customer's own Google Drive or SharePoint
- [ ] 3. Share the folder with the Helix Bot: `helix@adobe.com` (SharePoint) or `helix.integration@gmail.com` (Google Drive) and copy the URL.
   - 3a. Sharepoint: Give the Helix Bot: `helix@adobe.com` _edit_ permission to the folder and click on 'Copy Link' to retrieve the url.<br><img width="321" alt="image" src="https://user-images.githubusercontent.com/917628/163113849-1b2afe35-aac0-41bd-b680-f30d6faab529.png"><br>
    Note: The URL may need to be cleaned up to look like this: `https://adobe.sharepoint.com/sites/HelixProjects/Shared%20Documents/sites/<project-name>`.
   - 3b. Google Drive: Share the folder with the Helix Bot: `helix.integration@gmail.com` and copy the URL of the folder from the browser's address bar.<br><img width="737" alt="image" src="https://user-images.githubusercontent.com/917628/163114049-717faa59-b3b2-4dfe-b143-df8e005c7dd7.png">
- [ ] 4. In the GitHub repository, update the `/` mountpoint in `fstab.yaml` with the URL obtained in (3)
- [ ] 5. If the GitHub respository is not in [Helix Sites](https://github.com/organizations/hlxsites/repositories/new), invite the [Helix Bot](https://github.com/apps/helix-bot/installations/new) to it (only select repositories!)
- [ ] 6. Install the [Helix Sidekick](https://chrome.google.com/webstore/detail/helix-sidekick-beta/ccfggkjabjahcjoljmgmklhpaccedipo) Chrome extension
- [ ] 7. From the GitHub repository URL, click the extension and select _Add project_
- [ ] 8. In the content folder, create a new document named _index.docx_ (or `index` for Google Drive) and add some content to it
- [ ] 9. Preview the document by opening the sidekick in Word Online or Google Docs and clicking _Preview_
- [ ] 10. This will open a new browser tab with the project's homepage: `https://main--<project-name>--hlxsites.hlx.page/`

**Congratulations!** 🎉

### Set up communications

#### Set up Slack (most SMBs)

- [ ] 1. Create a new Slack channel `#helix-<project-name>` in the [Adobe Enterprise Support](https://adobeenterpri-izr7089.slack.com) instance
- [ ] 2. Add the site's URL to the channel topic: `Preview https://main--<project-name>--hlxsites.hlx.page/`
- [ ] 3. Type _Helix Bot_ (`@helix-bot`) _info_ and follow the its instructions to finish setting up the Slack channel for Helix
- [ ] 4. Ping a Helix team member and ask them to send invites to the customer contacts' external email addresses

#### Set up Teams (most Enterprise customers)

- [ ] 1. Create a new Teams channel `#helix-<project-name>` in Adobe's Teams instance
- [ ] 2. Add the site's URL to the channel's _About_ text: `Preview https://main--<project-name>--hlxsites.hlx.page/`
- [ ] 3. Send invites to the customer contacts' external email addresses