# Setup indexing in your helix project step by step

This document explains how to setup indexing in your project. You'll need the following:
- A shared documents folder in OneDrive
- A Github repository (defined by Github *owner*, *repository name* and *branch*, *tag* or *reference*)
- An Azure subscription
- An Adobe I/O Runtime namespace

## Prepare your Runtime namespace

Go to your Adobe I/O Runtime namespace and bind the helix-observation package into your namespace:

```
$ export WSK_CONFIG_FILE=<your namespace credentials>
$ wsk package bind /helix/helix-observation helix-observation
```

Continue setting up your namespace by adding two new packages `cache-flush` and `index-files` as described in [Add new observation task](./observation/howto-add-task.md) but do not create a OneDrive listener yet. In addition to those packages, you will now also have two action sequences `cache-flush-sequence` and `index-files-sequences`, respectively.

Go to your Azure subscription, and create a ServiceBus Queue with name `excel-indexer/<owner>/<repo>/<ref>`. This queue is used to synchronize access to the Excel sheet in OneDrive containing your index.

Finally, create a cron trigger in your namespace that runs periodically (e.g. every minute), and use it in a rule that will invoke the `index-files-sequences` sequence action.

## Install listener with Helix OneDrive Client

Go to https://github.com/adobe/helix-onedrive-cli, and download the Helix OneDrive Client. Do a `npm install -g` to install the client on your box. Follow the steps in that project's README to setup access. It is recommended to create a service account in your OneDrive subscription that will be allowed to access your shared documents folder and update the index.

After having setup access, create a OneDrive listener subscription as follows:

1. Go to your SharePoint `Documents` tab
1. Select an item in that list, and copy the link to it
1. Have the client resolve that link, and display the `DriveId` of your shared documents folder by entering:
```
$ 1d resolve <link>
```
4. Now create a subscription by using the command line:
```
$ 1d sub create \
   '/drives/<DriveId>/root' \
   'https://adobeioruntime.net/api/v1/web/<namespace>/helix-observation/onedrive-listener@latest/hook?owner=<owner>&repo=<repo>&ref=<branch|tag|ref>' \
   <secret>
```
If successful, changes made to your shared documents folder will be sent to the Helix OneDrive listener running in your namespace.

## Add a query definition to your GitHub repository

Copy a sample `helix-query.yaml` (there is none yet) to your GitHub repository's root folder. Adapt the property definitions with CSS selectors and value extraction as required, and specify what files you want to be indexed by using the `include` and `exclude` section.

Finally, create an empty XLSX file in your shared documents folder, copy the link and paste it as `target` property.
