---
name: New Helix Customer Project
about: Checklist for the creation of a new customer project
title: ''
assignees: ''

---

Start a new Helix customer project

- [ ] 1. Create a new GitHub repository, either under [Helix Sites](https://github.com/organizations/hlxsites/repositories/new) or the customer's own GitHub organization
- [ ] 2. Create a content folder, either in the dedicated SharePoint site for [Helix Projects](https://adobe.sharepoint.com/sites/HelixProjects/Shared%20Documents/Forms/AllItems.aspx?id=%2Fsites%2FHelixProjects%2FShared%20Documents%2Fsites)the customer's own Google Drive or SharePoint
- [ ] 3. Share the folder with `helix@adobe.com` (Helix Bot) and copy the URL
- [ ] 4. In the GitHub repository, update the `/` mountpoint in `fstab.yaml` with the URL obtained in (3)
- [ ] 5. If the GitHub respository is not in [Helix Sites](https://github.com/organizations/hlxsites/repositories/new), invite the [Helix Bot](https://github.com/apps/helix-bot/installations/new) to it (only select repositories!)
- [ ] 6. Install the [Helix Sidekick](https://chrome.google.com/webstore/detail/helix-sidekick-beta/ccfggkjabjahcjoljmgmklhpaccedipo) Chrome extension
- [ ] 7. From the GitHub repository URL, click the extension and select _Add project_
- [ ] 8. In the content folder, create a new document named _index_ and add some content to it
- [ ] 9. Preview the document by opening the sidekick in Word Online or Google Docs and clicking _Preview_
- [ ] 10. This will open a new browser tab with the project's homepage: `https://main--<project-name>--hlxsites.hlx.page/`

**Congratulations!** 🎉
