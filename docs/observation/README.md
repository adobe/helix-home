# Observation

**(DRAFT)**

Helix should allow users to register repository watchers, so that an action can be performed in response to changes in the content repository. Actions are implemented as Adobe I/O runtime actions in the customers namespace.

**Note**: in the future, helix might add a simpler way to deploy observation actions. for example using the `hlx` command line tool.

Examples for actions:

- invalidate cache
- check for broken links
- update indexes

## Overview

Helix uses OpenWhisk events (triggers, rules) to provide observation capabilities. It consists of the following building blocks:

- a _trigger_ in the customer's namespace for each repository or external source registered as mountpoint. The trigger has the name reflecting the observed repository, eg:  `{owner}--{repo}--{ref}`.
- a set of _receiver actions_ that will fire the trigger. These actions have implementations that are specific to the event provider and typically use web hooks to receive events from the event provider.
- rules that link the trigger events to custom _handler actions_.

# Implementation

## Setup

All repositories that need observation will have the **helix-bot** installed. The helix-bot doesn't know (and we don't store) the OpenWhisk credentials in the `helix-bot-config.yaml`. This is deliberate, to avoid distributing the credentials too broadly.

the OpenWhisk credentials are needed to:
- create a trigger that will receive the repository change events
- fire the trigger when a change event happens

To help with setting up observation, there is a `helix-services/observation` action, which helps managing the triggers:
- when creating the **trigger**, a random password is generated (`trigger-token`) and stored as annotation with the trigger.
- verify that the respective repository has a helix-bot setup
- update the `helix-bot-config` with the `trigger-namespace` and `trigger-token`

## Event dispatching

Events from external systems are dispatched to the trigger with the help of the `helix-services/observation` action. The observation action forwards events received via `POST /feed/:owner/:repo/:ref` to the respective trigger.

### Authorization

Only events that either include `trigger-token` or are properly signed are forwarded to the trigger.
- if the events contain a `x-trigger-token` header:
  - the `helix-services/observation` action reads (and caches) the `trigger-token` annotation from the respective trigger
  - if the trigger tokens don't match, it returns with a 403.

- if the events contain a `x-bot-signature` header:
  - the `helix-services/observation` action checks that the payload is signed with the bot's private key.
  - if the signature is invalid, it returns a 403.

**Note**:

currently, the helix-bot is the only client that uses the `helix-services/observation` action directly, so we could
drop the `trigger-token` feature. but for testing and potential other clients, it is still useful to have.

### helix project (not helix-pages)

#### bot setup
similar to the way the purge configuration is updated during `hlx publish`, the step also:
- ensures there's a trigger `{owner}--{repo}--{ref}` for all content repositories where a bot is installed
- if not, create a trigger using the ``helix-services/observation` action.

#### additional content change listeners
In order to register additional content change listeners, for example for onedrive, the setup is similar.

#### onedrive
for onedrive, each content repository that contains a `fstab.yaml` with mountpoints with a onedrive root need a _onedrive subscription_ that eventually invokes a change listener.
- during `hlx publish` all `{repository, driveRoot}` tuples are used to update the subscriptions.
- managing the subscriptions should probably happen in a separate action, but invoked by the `observation` action.
- each subscription needs to use the `onedrive-change-listener` action (webhook) in the respective customer namespace, so that the change listener can fire the trigger (without extra authentication). This action will be linked during `hlx deploy`.

#### dispatch

when the helix-bot receives a repository change event, it will
- read the `trigger-namespace` from the `helix-bot-config.yaml`
- sign the payload with its private-key
- `POST` the event information to the respective `observation` action eg:
   `https://adobeioruntime.net/api/v1/web/helix-test/observation/feed/myowner/myrepo/master`


### helix-pages project

The main use of observation for a helix-pages project is the updating of the index when content is modified. Since the helix-pages projects don't have their own OpenWhisk namespace, all triggers are installed in the `helix-pages` namespace.

setting up a trigger and creating the algolia index could be done automatically when the helix-bot is added to the repository. however, it is not clear yet how much power the bot should have here.

#### onedrive

for onedrive, each content repository that contains a `fstab.yaml` with mountpoints with a onedrive root need a _onedrive subscription_ that eventually invokes a change listener.
- each subscription needs to use the `onedrive-change-listener` action (webhook) in `helix-pages` namespace
- setting up the subscriptions is triggered manually (but could by triggered by the bot, when added to the repository or then the `fstab.yaml` is changed.

#### dispatch

when the helix-bot receives a repository change event, it will
- read the `trigger-namespace` and from the `helix-bot-config.yaml`
- sign the payload with its private-key
- `POST` the event information to the helix-pages `observation` action eg:
   `https://adobeioruntime.net/api/v1/web/helix-pages/observation/feed/myowner/myrepo/master`


