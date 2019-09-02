![](./6-sxb-hugues-de-buyer-mimeure-334018-unsplash.jpg)

# Project Helix Hackathon Part VI
Aug 26-30 2019 - Strasbourg, France

---


## Sixth Project Helix Hackathon

(See the [Hackathon Archive](.) for past Hackathons)

The sixth Project Helix Hackathon will happen in Strasbourg, France during the week of Aug 26th. If you feel like attending, please sign up [here](#Attendees).

### Location

> Where is this going to happen? Do you have a windowless conference room blocked out?

---

![Anticafé](https://www.anticafe.eu/wp-content/uploads/2018/09/anticafe_strasbourg-salle4.jpg)

---

The hackathon will take place at the [Anticafé Strasbourg](https://www.anticafe.eu/lieux/strasbourg-67000/) at [1 rue de la Division Leclerc 67000 Strasbourg](https://goo.gl/maps/W2QJRc3uW182).

The nearest airport is `SXB` (30 mins) which have connections from `AMS`. Other option: train from `BSL`, `ZUR` or `Paris`. The venue is 15 mins walk from train station.

### Agenda

> I hear this is a hackathon, are you going to hack all day?

Although this is a hackathon, we won't be hacking all the time, there will also be plenty of programming and coding.

| Time      | Monday         | Tuesday                          | Wednesday   | Thursday    | Friday    |
| --------: | -------------- | -------------------------------- | ----------- | ----------- | --------- |
|   Morning | -              | Helix Introduction & Demo Format | Coding <br> 11am: quarter retro and objectives | Coding <br> 10am: on-call debrief | Coding     |
| Afternoon | Airport Transfer & Arrival       | Coding <br> 2pm: Adobe Runtime Events call                          | Coding <br> 2pm: backlog grooming     | Coding <br> 2pm: demos     | Departure & Airport Transfer |
|   Evening | Set-up, drinks | Hacking                          | Team dinner - 7pm30 [L'Eveil des Sens](https://www.tripadvisor.com/Restaurant_Review-g187075-d792726-Reviews-L_Eveil_des_Sens-Strasbourg_Bas_Rhin_Grand_Est.html) - [map](https://goo.gl/maps/RwGFAifHDnmvBFr89) | Hacking     | -         |

### Lodging

Recommended hotels:

- Any hotel in Strasbourg city center should be at walking distance
- [Hôtel Les Haras](http://www.les-haras-hotel.com/) - (previous planned location) is at 10mins walking distance. Still a good choice.

### Goal

> What are you planning to show at the end of the week?

* Performathon: https://github.com/adobe/helix-cli/issues/935
* ~~helix-content API? https://github.com/adobe/helix-pipeline/issues/292~~
* Write integration tests for helix-pages (mainly tests on production): https://github.com/adobe/helix-pages/issues/8
* Look at parcel 2.0 and try to unify all scripts in one bundle (action) and eventually replace webpack.
* Adobe Runtime Events
* Conditonal language
* Authenticate GH access
* User journey of a customer onboarding on Helix Pages
* User journey of a customer onboarding with the editor

Sessions:
* On-call poc: debrief the 3-months trial, gather on-callees' feedback and define next steps (improve / change / do differently...)
* Backlog grooming: https://github.com/orgs/adobe/projects/2 has a LOT of issues, some of which are gathering dust...
* Retrospective of the last quater and objectives for the next quater
* Call with @francoisledroff (Adobe Runtime Events)


### Attendees

> Who is going to be there? Can I come?

1. @acapt
1. @simonwex
1. @trieloff
1. @rofe (from Tuesday 10 AM)
1. @koraa
1. @bdelacretaz (Tuesday-Thursday)
1. @stefan-guggisberg (Tuesday-Thursday)
1. @kamendola
1. @dominique-pfister (Tuesday-Thursday)
1. @francoisledroff (Wednesday-Thursday)
1. <del>@weilmic</del>
1. <del>@kgera</del> (Remote)
1. <del>@tripodsan</del> (partially remote)

We have room for 16 people. First come, first served :)

Please share this page with people inside Adobe that you'd like to invite. Add yourself to the list if you want to attend.

### Preparation

> What can I do to prepare for the Hackathon?

1. Read the `README.md` and linked vision documents in this repo
2. Join `#helix-chat` on Slack
3. Install the `hlx` Command Line app and create your first project
4. Comment on the GitHub issues you think would be good candidates for the Hackathon

### Demos

1. Helix & Adobe Runtime Events - https://bluejeans.com/s/iCkmi/
2. Helix Pages: Landing Pages user stories - same above, start at 13mins.
3. Loggly security analyzis: no recording. Check with @koraa for details
4. Helix Pages: Integration Test - https://bluejeans.com/s/L10CB

---

## Changes

For occasional contributors to Helix it can get hard to keep track of what has changed in Helix since the last hackathon. Below, you can find a curated (i.e. incomplete) list of some of the most interesting changes to Helix since [the fifth Hackathon, i.e. May 20th, 2019](5-bsl.md).

### Helix CLI

The most impactful change has been the move to a model of stronger service decomposition. Project Helix delivery now uses not just your pipeline actions (generated from your HTL and JSX templates), but also the static action, the Git Resolve Reference action, and a dispatch action to tie everything together. All actions except for your pipeline actions are provided as services by Project Helix and bound to your OpenWhisk namespace, so that you can always use the most recent version. You also have the ability to pin specific versions, which is useful during development to try out new features.

- Helix Pipeline has been updated to version 2.0.0 and no longer supports the merging of return values, which is a **breaking change**. The `context` now needs to be manipulated directly. [v3.0.0](https://github.com/adobe/helix-cli/releases/v3.0.0)
- the `--custom-vcl` parameter can be used to load custom VCL files when publishing. [v3.1.0](https://github.com/adobe/helix-cli/releases/v3.1.0)
- the static action is no longer built by default, but bound from a shared namespace, which makes upgrades easier and deploys a bit faster. [v3.2.0](https://github.com/adobe/helix-cli/releases/v3.2.0)
- The HTL processor has been hardened against XSS issues, but this means that adding DOM elements to the output now requires `@ context='unsafe'`, otherwise all `src` and `href` attributes will be removed from the injected DOM nodes. This is a **breaking change** [v4.0.0](https://github.com/adobe/helix-cli/releases/v4.0.0)
- `hlx up` now works without any custom templates or scripts at all, it will simply render the Helix Pages output. [v4.1.0](https://github.com/adobe/helix-cli/releases/v4.1.0)
- Helix Pipeline and HTL Engine have been upgraded to be fully DOM-based, which speeds up HTL processing by quite a bit. [v4.2.0](https://github.com/adobe/helix-cli/releases/v4.2.0)
- The `--minify` and `--cache` options have been removed from `hlx up` [v4.3.0](https://github.com/adobe/helix-cli/releases/v4.3.0)
- `--minify` can now be used for `hlx deploy` [v4.4.0](https://github.com/adobe/helix-cli/releases/v4.4.0)
- Introduces support for the new `helix-resolve-git-ref` service. By default, when deploying, the most recent version of the service will be used, but you can override it using the `--svc-resolve-git-ref` (this is mostly useful during development of said service) [v4.5.0](https://github.com/adobe/helix-cli/releases/v4.5.0)
- The Helix Publish service will now rely on a versioned service URL, making breakages due to updates more unlikely. [v4.6.0](https://github.com/adobe/helix-cli/releases/v4.6.0)
- Introduces support for the new `helix-dispatch` service. By default, the most recent version of the service will be used, but you can override it using the `--dispatch-version` parameter [v4.7.0](https://github.com/adobe/helix-cli/releases/v4.7.0)
- You don't have to run `hlx build` before running `hlx deploy` anymore. `hlx deploy` will do that for you. [v4.9.0](https://github.com/adobe/helix-cli/releases/v4.9.0)
- Adds support for Helix Pipeline 5.0.0, which also allows you to replace entire pipeline steps in your `pre.js`

### Helix Pipeline

There have been a number of breaking changes to the pipeline, affecting both the DOM output and the pipeline API itself.

- The pipeline now reports timing for each processing step using the `Server-Timing` header. This is useful for troubleshooting and finding slow steps in the pipeline. [v2.1.0](https://github.com/adobe/helix-pipeline/releases/v2.1.0)
- The pipeline now adds anchors to all headlines, enabling deep linking into the page. It also adds support for custom HTML elements in the HTML output. [v2.2.0](https://github.com/adobe/helix-pipeline/releases/v2.2.0)
- Pipeline processing is now based on DOM instead of string manipluation, making the overall pipeline faster [v2.5.0](https://github.com/adobe/helix-pipeline/releases/v2.5.0)
- Context dumps are no longer written to disk, only retained in memory [v3.3.0](https://github.com/adobe/helix-pipeline/releases/v3.3.0)
- The `response.errorStack` property now has additional information about errors that occurred during processing [v3.4.0](https://github.com/adobe/helix-pipeline/releases/v3.4.0)
- Hero images are no longer being wrapped in a `p` tag. [v3.5.0](https://github.com/adobe/helix-pipeline/releases/v3.5.0)
- Images are no longer forced to be responsive [v3.6.0](https://github.com/adobe/helix-pipeline/releases/v3.6.0)
- The calculation of cache keys has been unified with other Helix services and you can now store arbitrary data in the `context.content.data` object. [v3.7.0](https://github.com/adobe/helix-pipeline/releases/v3.7.0)
- The handling of sections has been simplified: sections are now part of the standard DOM output, but the `context.content.sections` array has been removed. This is a **breaking change** [v4.0.0](https://github.com/adobe/helix-pipeline/releases/v4.0.0)
- The pipeline API has been simplified: `before()`, `once()`, and `after()` functions have been replaced with `use()`. That is another **breaking change**. [v5.0.0](https://github.com/adobe/helix-pipeline/releases/v5.0.0)

### Helix Publish

The most notable change is the introduction of the Helix Dispatch service, which moved a lot of VCL logic into a serverless action. The change is mostly invisible to developers and entirely invisible to visitors.

### Helix Pages

Helix Pages is now available under `https://<user>-<repo>.hlx.page`

### Helix Log

[Helix Log](https://github.com/adobe/helix-log) is a new lightweight logging library used in Project Helix.

### Helix Resolve Git Ref

[Helix Resolve Git Ref](https://github.com/adobe/helix-resolve-git-ref) is a new service that resolves a Git SHA for a branch or tag name.

### Helix Dispatch

[Helix Dispatch](https://github.com/adobe/helix-dispatch) is a new service that coordinates a number of Helix Services during delivery.
