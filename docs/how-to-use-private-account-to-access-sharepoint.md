# Helix 3 - Use personal account to access sharepoint

## Overview

1. generate github personal access token -> `GH_TOKEN`
2. generate sharepoint refresh token -> `REFRESH_TOKEN`
3. encrypt refresh token `CREDENTIALS`
4. update fstab with `CREDETIALS`
5. update edge dict with `GH_TOKEN`

**Note**: that this will use your account for all access to sharepoint (admin, word2md, indexer).
The refresh token expires after 1-2 (?) days.

**Note**: there is currently no trace in the logs which user is used to fetch content from sharepoint.
If you want to ensure that your user is used, the easiest is to remove access for the `helix@adobe.com`
user. then all read and preview update requests must fail.

## Details

0. Generate a [github token](https://github.com/settings/tokens). let's call this `GH_TOKEN`
1. get https://www.npmjs.com/package/@adobe/helix-onedrive-cli 
```console
$npm -i -g @adobe/helix-onedrive-cli
...
$ 1d --version
1.9.12
```
2. log in 
```console
$ 1d login
To sign in, use a web browser to open the page https://microsoft.com/devicelogin and enter the code C2VPNR35W to authenticate.
Logged in as: Tobias Bocanegra (tripod@adobe.com)
$ 1d me
Logged in as: Tobias Bocanegra (tripod@adobe.com) 
``` 
3. get refresh token and create credentials
```console
$ REFRESH_TOKEN=$(jq  '{ r:.[0].refreshToken }' .auth.json)
$ echo $REFRESH_TOKEN
{ "r": "0.ASYAWht7-...." }
```
4. encrypt the creds using the admin
```console
$ echo $REFRESH_TOKEN | curl -si  -X POST --data-binary @- -H "x-github-token: $GH_TOKEN" https://admin.hlx3.page/encrypt  -H "content-type: application/json"
{
  "encrypted": "0Obexr7rFAP0JkFKCmvqeU2ghIMmaE....verylong...."
}
```
5. update `fstab.yaml` to include the encrypted credentials and commit
```yaml
mountpoints:
  /:
    url: "https://adobe.sharepoint.com/sites/cg-helix/Shared%20Documents"
    credentials:
      - 0Obexr7rFAP0JkFKCmvqeU2ghIMmaE....verylong....
```
6. test that the fstab is now in code bus
```console 
$ curl https://main--helix-test--tripodsan.hlx3.page/fstab.yaml --compressed
mountpoints:
  /:
    url: "https://adobe.sharepoint.com/sites/cg-helix/Shared%20Documents"
    credentials:
      - 0Obexr7rFAP0JkFKCmvqeU2ghIMmaE....verylong...
```
7. access to private document should now fail:
```console
curl -si https://admin.hlx3.page/preview/tripodsan/helix-test/main/private/
HTTP/2 404
content-type: text/plain; charset=utf-8
cache-control: no-store, private, must-revalidate
x-error: Handler onedrive could not lookup hlx:/tripodsan/helix-test/main/private/.
```
8. but using the gh-token should work:
```console
$ curl -si https://admin.hlx3.page/preview/tripodsan/helix-test/main/private/ -H "x-github-token: $GH_TOKEN"
HTTP/2 200
content-type: application/json
cache-control: no-store, private, must-revalidate
accept-ranges: bytes
date: Mon, 25 Oct 2021 17:07:36 GMT
via: 1.1 varnish
x-served-by: cache-mxp6976-MXP
x-cache: MISS
x-cache-hits: 0
x-timer: S1635181653.005803,VS0,VE3250
content-length: 1062

{
  "webPath": "/private/",
  "resourcePath": "/private/index.md",
....
```
9. add the github token to the `github_token` edge dict in the admin fastly service.

| key | value |
|-----|-------|
| `helix-test--tripodsan` | `ghp_....` |

10. wait 20-30 seconds, and test again:
```console
$ curl -si https://admin.hlx3.page/preview/tripodsan/helix-test/main/private/
HTTP/2 200
....
```
