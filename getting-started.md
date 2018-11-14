# Getting Started with Helix

This page demonstrates how to develop a website from scratch with Helix and deploy it in production.

## Develop your Helix Site

### Pre-Requisites

1. Node 8 (or later) and corresponding `npm` 

2. `git` & GitHub account

3. [Helix CLI](https://github.com/adobe/helix-cli) (see [Installation](https://github.com/adobe/helix-cli/blob/master/README.md#installation))

### Initialize your new project with sample code

Run:

```bash
hlx demo mytestproject
cd mytestproject
```

The `mytestproject` folder contains everything you need to start "coding" your website. Main entry point is the HTL template `src/html.htl` that defines the HTML of your webpages.

While Helix supports a pure local developement code base, it could be a good idea to set the `git remote`, the place where you will store your code.

* Go to [http://github.com/](http://github.com/) and create a new `mytestprojectcode` repository in your favorite org.
* Add the remote locally:

```bash
git remote add origin <mytestprojectcode_git_repo_url>
```

You can then commit and push your code.

### Develop locally

Run:

```bash
hlx up
```

Open the url [http://localhost:3000/](http://localhost:3000/)

A default page is rendered. You can change the HTL template `src/html.htl` and reload the page, changes will be automatically applied.

### Where is the content

#### Local content
During the project init, one file has been created locally: `index.md`. You can change the content and reload the page, changes will be automatically applied. You can create more files, like a `test.md` and request [http://localhost:3000/test.html](http://localhost:3000/test.html) to see the content.

This is really useful for local and offline development but this is not where the content should reside.

#### Content stored on github

* Go to [http://github.com/](http://github.com/) and create a new `mytestprojectcontent` repository in your favorite org. Add a `index.md` file in here.
* In mytestproject, edit the `helix-config.yaml`: uncomment the `contentRepo` property and put your repository URL.
* Restart `hlx up`

Open the url [http://localhost:3000/](http://localhost:3000/): the content is coming from the `mytestprojectcontent` git repository.

## Deploy your Helix Site

Only assumption taken for now is that we'll leverage `primordialsoup.life` Fastly service. Configuring a new service will be documented later.

Goal: create the new content-manageable website [](https://mytestproject.primordialsoup.life)


### Pre-requisites

* Get [access](https://github.com/adobe/project-helix/blob/master/SERVICES.md#fastly) to [primordialsoup.life](https://manage.fastly.com/configure/services/3l2MjGcHgWw5NUJz7OKYH3) Fastly service
* Get [access](https://github.com/adobe/project-helix/blob/master/SERVICES.md#adobe-io-runtime) to Adobe I/O Runtine.
* Install the [Helix Command Line Interface](https://github.com/adobe/helix-cli)

### Fastly

#### Onboarding a Domain

1. Log in to https://manage.fastly.com/account/tls/domains
2. Click "create TLS domain" https://manage.fastly.com/account/tls/domains/new
3. Enter the domain name with a wildcard qualifier: e.g. `*.experience-adobe.com`
4. Click next
5. In the list of domains, click "verify" next to your new domain name
6. Copy the `TXT` record and set it as a new DNS record (for `@`)
7. Be patient, as DNS propagation can take an hour
8. Click verify

#### Creating a Fastly Service

1. Log in to https://manage.fastly.com/services/all
2. Click "create service" or go to https://manage.fastly.com/configure/services/new
3. Enter name for the service, e.g. `experience-adobe.com`
4. Enter the domain, using the wildcard qualifier, e.g. `*.experience-adobe.com` (this allows you to easily map subdomains to strains)
5. Enter `example.com` for "Address" – this field will be overridden later on
6. Click "no, do not verify my TLS certificate"
7. Click "create"
8. Copy and remember the service ID `37kYmSvxGEHwUlWz7r8EWE`


### Go Live

#### Build and deploy the function

Run:

```bash
hlx build
hlx deploy --no-auto --wsk-namespace <your_openwisk_namespace> --wsk-auth <your_openwisk_auth>
```

`--dirty` might be needed if you have local changes which in theory should not be the case for a "go live".

This deploys your code to Adobe I/O Runtime. The action name should be something like `local--mytestproject--html` if you do not have set a git remote url or `/<your_openwisk_namespace>/<mytestprojectcode-git-url-dash-encoded>--html` if set it.

Useful debugging commands:

```bash
wsk action list # check if you command is in your namespace

wsk activation poll # show action activation logs
```

#### Test the function

This is purely for debugging purposes and check the internals

* Invoke the function

```
wsk action invoke -r --blocking <mytestprojectcode-git-url-dash-encoded>--html -p owner <your_org> -p repo mytestprojectcontent -p ref master -p path /index.md
```

This should return the rendered HTML.

* Test with URL

```bash
 wsk action get --url /<your_openwisk_namespace>/<mytestprojectcode-git-url-dash-encoded>--html
```

This outputs a URL that you can paste in your browser and add some few extra request parameters: `https://runtime.adobe.io/api/v1/web/<your_openwisk_namespace>/default/<mytestprojectcode-git-url-dash-encoded>--html?owner=<your_org>&repo=mytestprojectcontent&ref=master&path=/index.md`

* In both cases
  * The `owner`, `repo`, `ref` and `path` parameters refer to the content repository. You can create a new md file in the repo, change the `path` in the request and load the url: it will render this other page. Or try another ref (branch, tag or commit id)
  * having `wsk activation poll` running in a terminal should show you some logs of the rendering process (action activation)

#### Strain

##### Configure your strain

Last step to Go Live: configure a strain.

`hlx deploy` has generated a new file: `.hlx/strains.yaml`. It contains a default strain generated for you. This would be enough to setup a new Fastly service but we want to append our new strain to `primordialsoup.life`. For that, the file needs to contain the list of all the strains of the `primordialsoup.life` Fastly service, plus our new strain. For that, we need:

* Copy [strains.yaml](https://github.com/adobe/helix-cli/blob/master/test/integration/.hlx/strains.yaml) into the `.hlx/strains.yaml` file (overwrite).
* Add new strain config in `.hlx/strains.yaml`:

```yaml
- strain:
    name: mytestproject
    code: <your_openwisk_namespace>/default/<mytestprojectcode-git-url-dash-encoded>--
    condition: req.http.host == "mytestproject.primordialsoup.life"
    content:
      owner: <your_org>
      repo: mytestprojectcontent
      ref: master
      root: /
```

Before careful with the `code` format: no html at the end and the `/default/` is needed (I/O Runtime url and not the function name)

* Run:

```bash
hlx strain --fastly-auth <your_fastly_auth> --fastly-namespace 3l2MjGcHgWw5NUJz7OKYH3 --wsk-namespace <your_openwisk_namespace> --wsk-auth <your_openwisk_auth>
```

Note: 3l2MjGcHgWw5NUJz7OKYH3 is the id of the `primordialsoup.life` Fastly service.

Open [https://mytestproject.primordialsoup.life](https://mytestproject.primordialsoup.life): your site is Live!

##### Debug

Run:

```bash
curl -v -H "X-Debug: true" https://mytestproject.primordialsoup.life/index.html
```
