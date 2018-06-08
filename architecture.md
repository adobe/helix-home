# Overall thoughts

Generally a digital content management architecture consist of a an "authoring experience" and a "content delivery experience".

The authoring experience is used by the content managers, contributors, communicators to produce the digital experience for the consumer.

Underlying both there is a content repository that persists and manages the content in a serialized form implementing access control, versioning, search etc.

# Content Delivery Architecture

Historically in content management there are two flavors of the delivery architecture, (a) is called a "baking" architecture and (b) is called a "frying" architecture.

A baking architecture statically produces HTML (or other formats) and deploys it into a webserver (or other delivery mechanism), while a "frying" architecture dynamically responds to the incoming HTTP request and assembles the (HTML or other) response on the fly.

It is generally accepted that the "frying" architecture is more flexible and scalable, but initally a little bit more daunting, so the goal is to get to a frying architecture.

The initial block diagram looks as follows.

```

  ^               +-----------------------------+
  |               |                             |
  |               |   Browser, Client, Device   |
  |               |                             |
  |               +--------------+--------------+
Delivery                         |             
Layer                            |             
  |               +--------------v--------------+
  |               |                             |
  |               |             CDN             |
  |               |            Cache            |
  |               |                             |
  v               +--------------+--------------+
                                 |             
                                 |             
                  +--------------v--------------+
                  |                             |
Rendering         |          Serverless         |
Layer             |        Template Engine      |
                  |                             |
                  +--------------+--------------+
                                 |             
                                 |             
                  +--------------v--------------+
                  |                             |
Repository        |       API Abstraction       |
Layer             |      Git[Hub], Markdown     |
                  |                             |
                  +-----------------------------+
```                  
                  
                  
# Authoring Architecture

The authoring experience needs to start with very simple and efficient mobile interface, possibly support web for desktop.

Some form of intermittently disconnected experiences need to be supported along the lines of google doc style collaboration.

The general block diagram would look like this.

```
                  +-----------------------------+
                  |                             |
Authoring App     |         Native (+web)       |
                  |            Spark            |
                  |                             |
                  +--------------+--------------+
                                 |             
                                 |             
                  +--------------v--------------+
                  |                             |
Authoring         |          Serverless         |
Service           |        Authoring Service    |
                  |        Template Engine      |
                  |                             |
                  +--------------+--------------+
                                 |             
                                 |             
                  +--------------v--------------+
                  |                             |
Repository        |       API Abstraction       |
Layer             |      Git[Hub], Markdown     |
                  |                             |
                  +-----------------------------+
```                  


# Extensibility

Gradually providing extensibility on every layer is what allows for enterprise scalability from a functional standpoint.

Whenever extensibility is needed developers play a crucial role. GitHub gives developers a simple and native way to interact with both code (templates, authoring extensions, processing, ...) and content and avoid having to design, explain and teach how interactions work.

# Local Development

Serverless is great for deployment and scalability, but a dog develop and work with.

So it is important that we get to a place where a developer can start extending in minutes, without any unreasonable cloud dependencies against their local checkout in the filesystem.


# Publishing?

The concept of preparing content in one environment (branch, fork in GitHub speak) and a point in time publish (push or merge in GitHub speak) is a very typical scenario for larger bodies of work and bigger organizations.

The number of stages (think forks) as well as the approval processes (think PR in github) vary greatly.

In very small organizations the act of publishing is more of a hurdle than really desired, especially when it comes to quick iterations.

# Assets

## What is an Asset?

For the purposes of Helix, an asset is a piece of content that is not diffable at a line by line level in a human readable way. 

Selected Asset examples:

* Rasterized binary formats
  * Jpeg
  * GIF
  * PNG
* Text formats
  * PostScript 
  * SVG
* Composite formats
  * PDF
  * Indesign
  * PSD
  * Word
* Web formats
  * DCX

## Requirements for delivery via Fastly

Fastly has specific delivery requirements for dynamic delivery of assets. These assets must have a final rendition in [jpeg, png, gif, or webbp](https://docs.fastly.com/guides/imageopto-setup-use/serving-images.html#input-and-output-formats) format to deliver dynamic content. 

For delivering experiences from Helix, the content must either be stored directly in one of these formats or it must be a format natively supported by the browser/app. Careful consideration for the format of your assets must be considered for this purpose.

When in one of the supported Fastly formats, Fastly enables [resizing, cropping, and other transformations](https://docs.fastly.com/guides/imageopto-setup-use/serving-images.html#transformation-order)

## Location of Asset Storage

### Github Storage

Github does not offer renditions of asset formats, meaning that for any assets stored in git, they must be in one of the formats described in [Requirements for delivery via Fastly](#requirements-for-delivery-via-fastly) 

Github offers two storage models: [Git LFS](https://git-lfs.github.com) and direct storage in git.

Direct storage of assets in git is **NOT RECOMMENDED**. When assets are directly stored in git, all versions, of all assets, on all branches must be downloaded on any clone operation. With even moderately sized assets, this will quickly become untenable for content authors.

Git LFS allows git to store assets externally, but requires some setup on all machines.  
It is higly recommended to leverage LFS for all the binaries. This will keep the authoring workflows, consisting of git cloning and pushing, efficient and the entire git repo smaller. Assuming assets are stored in a subdirectory `/assets`, a `.gitattributes` file in there could enable LFS for all the binaries in it, not affecting markdown files.

Git LFS will still place the requirement on content authors to download the version of the binaries for the branch they are working on. For this reason, it is recommended not to store master assets in github, only renditions with adequate quality to serve to Fastly.

[Git LFS setup howto](https://help.github.com/articles/configuring-git-large-file-storage/)

### DAM Storage

DAM storage, through services such as AEM Assets is **RECOMMENDED** over git storage models, because the master digital asset can be ingested, stored, versioned, etc. without the limitations of git described in [Github Storage](#github-storage). 

Most DAM services provide the ability to access a rendition of the content in one of the [Fastly formats](#requirements-for-delivery-via-fastly).

To provide responsive experiences via Fastly metadata such as crop coordinates, alt texts, etc. through sidecar files or an API must be provided in association with an asset.

# Open Questions

- What is the .md of something like an adobe.com website going to look like? Can we keep it still readable?
- How does serverless work in a request/response environment?
- How do we have to batch-up edits into commits and PRs to get to a place where it makes sense?
- Is +1m md files in a single github repo feasible?
- Do repository references work virtually tie together code and content?
- Should very small, non-extensible (think Spark Page) projects be persisted as md outside of git (probably)?
- How would Helix read the crop metadata on an asset and provide this to Fastly? What about other responsive experiences?
- We are conveying a lot of specific details how Fastly serves assets to our implementations. Is there a better way to make this generic, or translate/transcode content that fastly doesn't serve?
- What about video delivery?
- _<add questions here>_
