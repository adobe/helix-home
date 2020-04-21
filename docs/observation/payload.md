# Format of observation payload

Notifications received by a source repository, such as GitHub, OneDrive, Google Drive, etc. have a provider specific format, e.g. OneDrive uses the format described on Microsoft's [docs](https://docs.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0&tabs=http) site. When those notifications are received in the respective listener (e.g. `onedrive-change-listener` for OneDrive), they get converted to a portable format before sent to the next stage. The format has the following properties:

| Name | Type | Description |
|------|------|-------------|
| `owner` | _string_ | GitHub owner |
| `repo` | _string_ | GitHub repository name |
| `ref` | _string_ | GitHub reference or branch name |
| `observation` | _object_ | [observation](#observation) data, see next section |

## Observation

The `observation` contains the changes observed, the mountpoint affected and provider specific data:

| Name | Type | Description |
|------|------|-------------|
| `type` | _string_ | provider type, possible values are `onedrive`, `github` or `google`. |
| `changes` | _array_ | [changes](#changes) observed, see below |
| `mountpoint` | _object_ | [mountpoint](#mountpoint) affected, see below |
| `provider` | _object_ | provider specific information |

An example observation for OneDrive might look as follows:

```
{
  "type": "onedrive",
  "changes": [
    ...
  ],
  "mountpoint": {
    ...
  },
  "provider": {
    "driveId": "b!-RIj2DuyvEyV1T4NlOaMHk8XkS_I8MdFlUCq1BlcjgmhRfAj3-Z8RY2VpuvV_tpd"
  }
}
```

## Changes

The `changes` array contains an entry per change seen since the last batch of changes was sent. They have the following properties:

| Name | Type | Description |
|------|------|-------------|
| `path` | _string_ | item path, **required** for _added_ items, **optional** for _modified_ or _deleted_ items |
| `time` | _string_ | time of change |
| `type` | _string_ | change type. one of `modified`, `added`, `deleted`<sup>1</sup> |
| `uid`  | _string_ | unique identifier, required |

<sup>1</sup> If the provider is not able to distinguish between `added` and `modified`, `modified` should be used.

An example follows:
```
[{
  "path": "/myshare/doc1.docx",
  "time": "2020-03-03T20:05:23Z",
  "type": "modified",
  "uid": "CvaY0bVa2e5dU6VT"
},
{
  "time": "2020-03-03T20:05:26Z",
  "type": "deleted",
  "uid": "T4iuC3ycQbEG+TY/"
}]
```

## Mountpoint

The `mountpoint` contains the information how paths in the changes array should be translated by replacing a prefix matching `root` with `path`:

| Name | Type | Description |
|------|------|-------------|
| `root` | _string_ | Root folder path in the external provider |
| `path` | _string_ | Path where root folder is mounted to |


 e.g. for the following mountpoint:
 ```
{
  "root": "/myshare",
  "path": "/ms/"
}
```
and incoming change:

```
{
  "path": "/myshare/doc1.docx",
  "time": "2020-03-03T20:05:23Z",
  "type": "modified"
},
```
the translated path is `/ms/doc1.docx`.
