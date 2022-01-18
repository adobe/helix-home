# Breaking Change Handling

Unfortunately, it's sometimes necessary to break backward compatibility due to many reasons.
In order to manage those breaking changes and shield customers from those as long as possible,
helix has a _version locking_ mechanism in place.

## Version locking the pipeline

### Query Parameter

Version locking the pipeline is achieved using 2 mechanisms. A short term of overriding the
version of the pipeline-service, one can request a preview page with the `hlx-pipeline-version`
query parameter. For example:

```
https://main--helix-website--adobe.hlx3.page?hlx-pipeline-version=ci2233
```

would select the `ci2233` version of the pipeline.  

### Edge dictionary entry

A second, midterm mechanism of overriding the default version is achieved by adding an entry to the
`version_lock` edge dictionary in the `hlx3.page` fastly service. the key of the entry follows the
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

> Note: currently, there is no automatic breaking change management planned. Customers who need special pipeline versions need to get in contact with helix devops.


## Version locking the admin API

_there is no version locking for the admin API in place yet. but in needed, can be achieved using either path segments or accept headers._

## Version locking publishing

there might be situations, where breaking changes affect the way the authoring documents are converted and stored in the content-bus.
So far there is no mechanism in place, but could be controlled in a similar way as the pipeline version:

- support a query parameter that would be included in the admin API requests by the sidekick to select a special content producer version.
- support an entry in the project config (`/.helix/config.json`) that locks the version of a content producer
