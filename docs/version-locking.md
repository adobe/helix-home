# Helix Pages Version and Feature Locking

This document describes the version locking feature for helix pages. 

The goal of this feature is to provide customers a way to select, test, and develop against a selected _version_ or _feature_ of helix pages.

There are several aspects to this feature:

- Definition of a version set (by the helix developer team)
- Rendering of pages using a specific version set
- Selecting default version set for a helix pages project
- Developer experience of a helix pages customer

## Definition of a Version Set

The parts that influence how a resource is rendered in helix pages are:

- a set of serverless services (dispatch, content-proxy, static, redirect, word2md, gdocs2md, etc)
- the helix pages code (pre.js)
- the helix-pipeline version
- the VCL deployed via helix-publish to fastly (inner-cdn config)
- the project code (helix-query.yaml, head.html, scripts, css, etc...)

A helix pages _version_ or _feature_ is defined by:

- an identifier (eg. breaking-december)
- a set of services with specific versions (eg. word2md v20201201.1, content-proxy v20201201.4)
- a helix pages code branch

The version set definition is recorded in a `versions.yaml` which is stored in the respective helix pages code branch. For example, a `breaking-december` _version_ would have `versions.yaml` in the `breaking-december` branch in the helix-pages repo:

```yaml
name: breaking-december
issues: 
  - "https://github.com/adobe/helix-home/issues/159"
versions: 
  - pipeline: 
      description: "Component Tables"
      url: "https://github.com/adobe/helix-pipeline/tree/breaking-december"
      version: 202012.3

  - word2md: 
      description: "Component Tables"
      url: "https://github.com/adobe/helix-word2md/tree/breaking-december"
      version: 202012.1

  - content-proxy: 
      description: "Disable all caching"
      url: "https://github.com/adobe/helix-content-proxy/tree/breaking-december"
      version: 202012.4

```

### Automatic generation of the version set definition

Ideally, the version set is automatially generated, based on tags or similar in github pull requests an or issues. This is left open for now and can be added at a later stage.


## Rendering using a specific set

Ultimately, redering a specific set of services is initiated by the VCL via strains. By selecting the specific strain, the respective `x-ow-version-lock` header is sent to the respective `helix-dispatch` service. The strain is selected, either via the helix pages branch logic, or a strain condition, or a pre-flight condition.

The _deploy_ script in the helix-pages repository ensures that the `versions.yaml` of the respective branch is properly transformed into a _strain_ in the `helix-config.yaml`.
If the `versions.yaml` also contains an entry for the `pipeline`, it updates the `package.json`. 

(the publishing step will update the respective edge dictionary for that strain).

### Missing features

- [ ] The `ow-x-version-lock` header must be strain dependent
- [ ] The `dispatch` version must be strain dependant

## Selecting the (default) version set

As long as the project is _surfed_ on the specific version branch (eg `https://breaking-december--pages--adobe.com/`), the branch name automatically selects the version set (via the strain). But once the feature branch is merged back to `main`, this information is lost. 

Using preflight requests and helix pages version picker service allow to define additional information that can be used in strain conditions:

The default branch of a helix pages project might have a `helix-version.txt` file which contains the name of a version set (eg. `breaking-december`). The version picker service will respond with a `x-helix-version` header, containing the name of the version set. eg: `x-helix-version: breaking-december`.

The preflight request login in the VCL will set the `preflight.x-helix-version` condition which will select the correct strain.

## Implications

### Locked-in problem

Once a project chooses a specific version, eg. the `helix-version.txt` contains the `breaking-december`, helix needs to ensure that the respective version set remains active and once removed, the default version set needs to be compatible with the version set.

Example:

1. helix creates the `breaking-december` version set that contains backward incompatible changes.
2. project creates a `breaking-december` branch and (automatically) a `helix-versions.txt` (containing `breaking-december`). 
3. project updates its client code until the breaking issues are fixed.
4. project merges its `breaking-december` branch back to `main`, which now contains the `helix-versions.txt` with the `breaking-december` version name.
5. helix creates the `breaking-january` version set
6. project **doesn't update** to breaking-january
7. helix creates the `breaking-february` version set
8. `breaking-december` will now be the new default and all services are updated accordingly.

And so on... As long a 1 project still contains a reference to an old _version_, the strain in helix-pages needs to remain. The risk here is to reach the limit of the edge dictionaries or to loose oversight.


### Missing features

- [ ] The `helix-version-picker` https://github.com/adobe/helix-home/issues/167
- 


## Developer Experience (customer)

Ideally the developer just uses git and the helix command line to:

1. checkout his project
2. running `hlx` tells him that there are new upcomming breaking changes
3. he can choose to automatically _start testing_ the new changes. the cli would create the respective branch, create the `helix-versions.txt`, update helix pages
4. the simulator works correctly on the test branch and the developer can update his code so that his website works again
5. the developer can push the branch to github and test the changes remotely.
6. once satisfied, the developer creates a PR can eventually merges the changes back to `main`.



## Discussions, open Questions

**TODO**: discuss default vs locked service versions: Should every _release_ by defined by a version manifest that selects the service versions, instead of using the symlinks ?
