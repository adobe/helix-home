# Breaking Change Handling

Unfortunately, it is sometimes necessary to break backward compatibility due to many reasons.
In order to manage those breaking changes and shield customers from those as long as possible,
franklin has a _version locking_ mechanism in place.

## Version locking the pipeline

### Query Parameter

Version locking the pipeline is achieved using 2 mechanisms. For A short term overriding the
version of the pipeline-service, one can request a preview page with the `hlx-pipeline-version`
query parameter. For example:

```
https://main--helix-website--adobe.hlx.page?hlx-pipeline-version=ci2233
```

would select the `ci2233` version of the pipeline.  

### Edge dictionary entry

A second, midterm mechanism of overriding the default version is achieved by adding an entry to the
`version_lock` edge dictionary in the `*.hlx.page` fastly service. the key of the entry follows the
`ref--repo--owner` format, where the `ref` is optional. For example


| Key                                    | Value    |
|----------------------------------------|----------|
| `*`                                    | `v4`     |
| `breaking-march--helix-website--adobe` | `ci9911` |
| `blog--adobe`                          | `v3`     |

would:
- select `ci9911` for the `breaking-march` branch of the `helix-website`.
- select `v3` for the `blog` (e.g. a project that wasn't able to upgrade yet)
- select `v4` by default.

#### Automatic Provisioning

In order to select a different version, the `helix-version.txt`, located in github, can contain the version name.
as soon as it is updated in github, the helix admin will update the edge dicts accordingly. for changes on the `main` branch
a `*--repo--owner` entry is added; for changes on other branches a `ref--repo--owner` entry is added.

## Version locking the admin API

The admin API has the same version locking capabilities as the pipeline service:

```
https://admin.hlx.page/status/adobe/helix-website/main?hlx-admin-version=ci2233
```

## Version locking publishing

there might be situations, where breaking changes affect the way the authoring documents are converted and stored in the content-bus.
So far there is only a ad-hoc, short term, version selection available, by adding a `hlx-word2md-version` or `hlx-gdocs2md-version`
parameter to the `preview` route when invoking the admin API manually.

In the future, there could be the possibility to add config to the `helix-version.txt` file as well.

