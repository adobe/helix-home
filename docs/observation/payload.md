# Format of observation payload

Notifications received by a source repository, such as GitHub, OneDrive, Google Drive, etc. have a provider specific format, e.g. OneDrive uses the format described on Microsoft's [docs](https://docs.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0&tabs=http) site. When those notifications are received in the `onedrive-change-listener`, they get converted to a portable format before sent to the next stage, e.g.:

```
{
  "type": "onedrive",
  "owner": "me",
  "repo": "myrepo",
  "ref": "master",
  "changes": [
    ...
  ],
  "mountpoint": {
    "path": "/ms/",
    "root": "/myshare"
  }
}
```
The `changes` array contains all an entry per change seen since the last batch of changes was sent, e.g. for OneDrive:

```
{
  "path": "/myshare/doc1",
  "time": "2020-03-03T20:05:23Z",
  "type": "modified",
  "provider": {
    "sourceHash": "CvaY0bVa2e5dU6VT"
  }
},
{
  "time": "2020-03-03T20:05:26Z",
  "type": "deleted",
  "provider": {
    "sourceHash": "T4iuC3ycQbEG+TY/"
  }
}
```

The `provider` section contains provider specific information that might be required for downstream handlers. In the
example above, a `sourceHash` is included that uniquely identifies items. The `path` property must be available for
added or modified items, but may be missing for deleted items, if that information is no longer available.

The `mountpoint` contains the information how paths in the changes array should be translated by replacing a prefix matching `root` with `path`, e.g. for the following change:
```
{
  "path": "/myshare/doc",
  "time": "2020-03-03T20:05:23Z",
  "type": "modified"
},
```
the translated path is `/ms/doc`.
