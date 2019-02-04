# Getting Started with Helix

This page demonstrates how to develop a website from scratch with Helix and deploy it in production.

## Develop your Helix Site

### Pre-Requisites

* [Git](https://git-scm.com/) should be [installed](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git) and [setup](https://git-scm.com/book/en/v2/Getting-Started-First-Time-Git-Setup) on your machine. You also need a [GitHub](https://github.com/join) account.


### Install the Helix Command Line Interface
Install `hlx` as a global command (Node 8 or higher required)
```bash
$ npm install -g @adobe/helix-cli
```
Alternatively, you can install the latest binary version of `hlx` using `curl` (no Node required):
```bash
$ curl -sL http://www.project-helix.io/cli.sh | sh
```

### Initiate your Helix project

Create a demo project:
```bash
hlx demo mytestproject
cd mytestproject
hlx up
```

This will open your default browser with [http://localhost:3000/](http://localhost:3000/).

The `mytestproject` folder contains everything you need to start coding. For instance, you can change the HTL template `src/html.htl` and refresh the page. Changes will be applied automatically.

Helix also created a content file locally: `index.md`. You can change its markdown content and refresh the page. Changes will be applied automatically. You can also create additional files. For example, in order to render a `test.md`, request [http://localhost:3000/test.html](http://localhost:3000/test.html).

## Set up GitHub repositories

### Commit your code

While Helix supports a pure local developement code base, you will eventually need a code repository in GutHub in order to be able to deploy and publish your project:
1. Go to [http://github.com/](http://github.com/) and create a new `mytestprojectcode` repository in your favorite org
2. Add the remote locally:
```bash
git remote add origin <mytestprojectcode_repo_url>.git
```
(Make sure your GitHub URL ends with `.git`)
3. Commit and push your code:
```bash
git commit -m"initial commit"
git push --set-upstream <mytestprojectcode_repo_url>.git --force --allow-unrelated-histories
```
(Double check if your `git remote` is set to the correct GitHub repository!)
4. If you don't have a `helix-config.yaml` file yet, you can now have Helix create one for you:
```bash
hlx up --save-config
```
5. Point the `&defaultRepo` in `helix-config.yaml` to your code repository:
```
    - &defaultRepo "<mytestprojectcode_repo_url>.git#master"
```

### Commit your content

Having content locally in your code checkout is practical for local and offline development, but it should eventually move to its dedicated GitHub repository.

1. Go to [http://github.com/](http://github.com/) and create a new `mytestprojectcontent` repository in your favorite org. Add an `index.md` file in there.
2. Add the content repository to the `default` strain in `helix-config.yaml`: 
```
    content: <mytestprojectcontent_repo_url>#master
```
3. Restart `hlx up`

[http://localhost:3000/](http://localhost:3000/) will now show the content coming from the `mytestprojectcontent` GitHub repository.

## Deploy your Helix Site

### Pre-Requisites

1. Get [access](https://github.com/adobe/project-helix/blob/master/SERVICES.md#adobe-io-runtime) to Adobe I/O Runtine.
2. Get [access](https://github.com/adobe/project-helix/blob/master/SERVICES.md#fastly) to Fastly.
3. Install `wsk`
    1. `brew install wsk` (or [download](https://github.com/apache/incubator-openwhisk-cli/releases) and install OpenWhisk-client manually)
    2. `wsk property set --apihost runtime.adobe.io --auth <wsk_auth> --namespace <wsk_namespace>`

### Fastly Setup

#### Onboarding a Domain

1. Log in to https://manage.fastly.com/account/tls/domains
2. Click "create TLS domain" https://manage.fastly.com/account/tls/domains/new
3. Enter the domain name with a wild card qualifier: e.g. `*.experience-adobe.com`
4. Click next
5. In the list of domains, click "verify" next to your new domain name
6. Copy the `TXT` record and set it as a new DNS record (for `@`)
7. Be patient, as DNS propagation can take an hour
8. Click verify

#### Creating a Service

1. Log in to https://manage.fastly.com/services/all
2. Click "create service" or go to https://manage.fastly.com/configure/services/new
3. Enter name for the service, e.g. `helix-demo.xyz`
4. Enter the domain, using the wildcard qualifier, e.g. `*.helix-demo.xyz` (this allows you to easily map subdomains to strains)
5. Enter `example.com` for "Address" – this field will be overridden later on
6. Click "no, do not verify my TLS certificate"
7. Click "create"
8. Copy and remember the service ID


### Deploy the action

Deploy your Helix Site:

```bash
hlx deploy --wsk-namespace <your_openwisk_namespace> --wsk-auth <your_openwisk_auth>
```

The `--dirty` option might be needed if you have uncommitted changes, which should not be the case for a "go live".

This deploys your code to Adobe I/O Runtime. The action name should be something like `local--mytestproject--html` if you do not have set a GitHub remote url, or `/<your_openwisk_namespace>/<mytestprojectcode-git-url-dash-encoded>--html` if set. To be sure, copy it from `default.code` in `.hlx/strains.json` generated by `hlx deploy`.

Useful debugging commands:

```bash
wsk action list # check if your action is in your namespace

wsk activation poll # show action activation logs
```

#### Test the function

This is purely for debugging purposes and checking the internals.

Invoke the action:

```
wsk action invoke -r --blocking <mytestprojectcode-git-url-dash-encoded>--html -p owner <your_org> -p repo mytestprojectcontent -p ref master -p path /index.md
```

This should return the rendered HTML.

Test with URL:

```bash
 wsk action get --url /<your_openwisk_namespace>/<mytestprojectcode-git-url-dash-encoded>--html
```

This outputs a URL that you can paste in your browser and add some few extra request parameters: `https://runtime.adobe.io/api/v1/web/<your_openwisk_namespace>/default/<mytestprojectcode-git-url-dash-encoded>--html?owner=<your_org>&repo=mytestprojectcontent&ref=master&path=/index.md`

Note:
  * The `owner`, `repo`, `ref` and `path` parameters refer to the content repository. You can create a new md file in the repo, change the `path` in the request and load the url: it will render this other page. Or try another ref (branch, tag or commit id)
  * Running `wsk activation poll` in a terminal should show you some logs of the rendering process (action activation)


### Publish your Helix Site

```bash
hlx publish --wsk-namespace <your_openwisk_namespace> --wsk-auth <your_openwisk_auth> --fastly-namespace <your_fastly_namespace> --fastly-auth <your_fastly_service_id>
```

Open https://<your_domain>/ in the browser: your site is now live!

#### Debug

Run:

```bash
curl -v -H "X-Debug: true" https://<your_domain>/
```
