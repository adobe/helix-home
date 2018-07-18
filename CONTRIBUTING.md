# Contributing to Project Helix

Thank you for your interest in contributing to Project Helix. As an Open Development project, Project Helix is not just open to contributions, but we actively encourage and seek contributions from across Adobe. **We are thrilled to have you on board.**

## Understanding Project Helix

1. Start with [whitepaper.md](whitepaper.md) to understand why we build Project Helix
2. Then read [manifesto.md](manifesto.md) to learn about the unique way the team works
3. Get an overview of the architecture from [prototypes/README.md](prototypes/README.md)
4. Also check out [SERVICES.md](SERVICES.md) for a list of external services we are using

## Where to contribute in Project Helix

Most of the code of Project Helix and most of the most exciting areas for contributions are already Open Source, so some of the most interesting areas for contributions are:

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

## How to communicate with Project Helix

1. Join us in the [`#helix-chat`](https://adobe.slack.com/messages/C9KD0TT6G/) Slack channel (Enterprise Grid)
2. Come to the next [Project Helix Hackathon](hackathon.md)

## Development - Check out all modules

### Related Repositories

This umbrella project contains a [gitslave](http://gitslave.sourceforge.net) config that can be used to check out all relevant modules that are hosted as individual repositories:

- https://github.com/adobe/helix-cli
- https://github.com/adobe/petridish
- https://github.com/adobe/hypermedia-pipeline
- https://github.com/adobe/git-server
- https://github.com/adobe/helix-helpx
- https://github.com/adobe/parcel-plugin-htl
- https://github.com/adobe/parcel-plugin-jst
- https://github.com/adobe/openwhisk-loggly-wrapper
- https://github.com/adobe/htlengine

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