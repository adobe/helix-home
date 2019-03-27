# Contributing to Project Helix

Thank you for your interest in contributing to Project Helix. As an Open Development project, Project Helix is not just open to contributions, but we actively encourage and seek contributions from across Adobe. **We are thrilled to have you on board.**

## Where to contribute in Project Helix

A lot of the code of Project Helix and many of the most exciting areas for contributions are already Open Source, so some of the more interesting areas for contributions are:

1. [HTL Engine](https://github.com/adobe/htlengine)
2. [HelpX on Helix](https://github.com/adobe/helix-helpx)
3. [Petridish](https://github.com/adobe/petridish)
4. [OpenWhisk Loggly Wrapper](https://github.com/adobe/openwhisk-loggly-wrapper)
5. [HTL/Sightly Plugin for Parcel](https://github.com/adobe/parcel-plugin-htl)
6. [Hypermedia Pipeline](https://github.com/adobe/hypermedia-pipeline)
7. [Helix CLI](https://github.com/adobe/helix-cli)
8. [git-server](https://github.com/adobe/git-server)
9. [Parcel Plugin JST](https://github.com/adobe/parcel-plugin-jst)

There is also a [list of all Project Helix-related repositories on GitHub](https://github.com/search?q=topic%3Ahelix+org%3Aadobe&type=Repositories).

The Project Helix repository contains the few parts that remain closed source, including

1. `/prototypes/custom-dockerimage`: a custom docker image for Adobe I/O Runtime
2. `fastly`: the default Fastly configuration
3. `logging`: a log processing pipeline that gets request logs from Fastly and pushes them to Azure CosmosDB

## How to contribute to Project Helix

For each of the Open Source projects, refer to the project's `CONTRIBUTING.md`. For Project Helix:

1. Create a GitHub issue for stuff that you want to work on, even if it's just an idea
2. Open Pull Requests for code changes
3. Make sure your code is tested and covered by CircleCI

A good start point is to check the issue tagged with ["good first issue"](https://github.com/search?q=is:open+repo:%22adobe/project-helix%22+repo:%22adobe/htlengine%22+repo:%22adobe/helix-cli%22+repo:%22adobe/git-server%22+repo:%22adobe/petridish%22+repo:%22adobe/parcel-plugin-htl%22+repo:%22adobe/helix-helpx%22+repo:%22adobe/parcel-plugin-jst%22+repo:%22adobe/openwhisk-loggly-wrapper%22+repo:%22adobe/helix-dockerimage%22+repo:%22adobe/parcel-plugin-jst%22+repo:%22adobe/hypermedia-pipeline%22+label%3A%22good+first+issue%22&type=Issues).

## How to communicate with Project Helix

1. Join us in the [`#helix-chat`](https://adobe.slack.com/messages/C9KD0TT6G/) Slack channel (Enterprise Grid)
2. Come to the next [Project Helix Hackathon](/hackathons)

## Development - Check out all modules

### Related Repositories

This umbrella project contains a [gitslave](http://gitslave.sourceforge.net) config that can be used to check out all relevant modules that are hosted as individual repositories:

**Code**
- https://github.com/adobe/helix-cli
- https://github.com/adobe/helix-simulator
- https://github.com/adobe/helix-shared
- https://github.com/adobe/hypermedia-pipeline
- https://github.com/adobe/git-server
- https://github.com/adobe/parcel-plugin-htl
- https://github.com/adobe/parcel-plugin-jst
- https://github.com/adobe/openwhisk-loggly-wrapper
- https://github.com/adobe/helix-dockerimage
- https://github.com/adobe/htlengine

**Projects**
- https://github.com/adobe/helix-helpx
- https://github.com/adobe/project-helix.io

**Related**
- https://github.com/tripodsan/probot-serverless-openwhisk
- https://github.com/tripodsan/probot-openwhisk-docker

### Setup

This requires [gitslave](http://gitslave.sourceforge.net).

#### Mac

    $ brew install gitslave

### Working with gitslave

When checking out for the first time, do this:

    $ git clone git@github.com:adobe/project-helix.git
    $ cd project-helix
    $ gits populate

To update later, do this (inside the `project-helix` dir):

    $ git pull && gits pull


If the `.gitslave` config has changed, just re-populate and pull again:

    $ gits populate && gits pull

## Debugging Tips

Some random debugging tips for Helix developers

### Debug Header for Fastly

When making requests to a site in production, you can add the `X-Debug` HTTP header to your request to get more information in the response headers.

### Using `npm link` for Modules

When developing locally it might be neccessary to make changes to a downstream dependency. `npm link` allows you to let a local checkout of an NPM project satisfy a dependency in your `package.json`.

Let's say you want to work on `parcel-plugin-htl`, but you need access to `htlengine` code:

```bash
# check out htlengine
$ git checkout `https://github.com/adobe/htlengine.git`
$ cd htlengine
$ npm install
# make this version of htlenine available to all npm projects
$ npm link
# we're done here, let's go back to parcel-plugin-htl
$ cd ../parcel-plugin-htl
# tell npm to use the htlengine from above instead of downloading a package from npmjs
$ npm link @adobe/htlengine
```

# Releasing

## NPM Packages

Package version follow [semantic versioning](https://github.com/npm/node-semver). But since most of
our packages still are on 0.x, it is rather good practice to use minor / patch versions accordingly.

Our CI automatically creates a new [pre-release](https://semver.org/#spec-item-9) versions for every 
merged pull request.  The _pre-release_ versions have the form `x.y.z-pre.c` (where `c` is a counter). 
The subsequent release version `(x.y.z)` will take [precedence](https://semver.org/#spec-item-11)
over the _pre-release_ version. 

the _pre-release_ versions are attributed with the [dist-tag](https://docs.npmjs.com/cli/dist-tag) `@next`.

### How to cut a release

Based on the changes that follow up a release, we used [semantic versioning](https://github.com/npm/node-semver)
to define the next release type: `major`, `minor` or `patch`: 

> **Note:** The packages have a `postversion` script that will push the updated package.json along
> with the git-tag. so no need to do this manually
  
Creating a _patch_ release:
   
```bash
$ npm version patch
$ npm publish --tag latest --access public
```

Creating a _minor_ release:
   
```bash
$ npm version minor
$ npm publish --tag latest --access public
```

Creating a _major_ release:
   
```bash
$ npm version major
$ npm publish --tag latest --access public
```

### Adding release notes

It is good practice to write some release notes on git for the respective release.
For example: https://github.com/adobe/helix-cli/releases/tag/v0.3.1

In the future, the release notes can be generated automatically from the information from git issues
and commits.






