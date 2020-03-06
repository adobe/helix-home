# Format of observation payload

Notifications received by a source repository, such as GitHub, OneDrive, Google Drive, etc. have a provider specific format, e.g. OneDrive uses the format described on Microsoft's [docs](https://docs.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0&tabs=http) site. When those notifications are received in the `onedrive-change-listener`, they get converted to a portable format before sent to the next stage, e.g.:

```
{
  "type": "onedrive",
  "provider" {
    "driveId": "..."
  },
  "owner": "me",
  "repo": "myrepo",
  "ref": "master",
  "mountpoint": {
    "path": "/ms/",
    "root": "/myshare"
  },
  "changes": [
    ...
  ],
}
```
The `provider` section contains provider specific information that might be required for downstream handlers.

The `mountpoint` contains the information how paths in the changes array should be translated by replacing a prefix matching `root` with `path`, e.g. for the following change:
```
{
  "id": "...",
  "path": "/myshare/doc.md",
  "time": "2020-03-03T20:05:23Z",
  "type": "modified"
},
```
the translated path is `/ms/doc.md`.
