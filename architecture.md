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

Binary assets can either be in an:

* _internal DAM_: included in the git content repository itself, often in a dedicated subfolder `/assets`, and linked as relative paths in markdown
* _external DAM_: stored by some full fledged asset management solution or Dropbox-like drive, in which case they are referenced by some kind of URL

In both cases, a delivery compatible raster image (jpeg or png) or servable binary has to be provided by the DAM. Meaning Helix would not be responsible for turning a PSD into a JPEG. _Delivery compatible_ means the CDN (Fastly) can handle it and apply resize, crop and other dynamic & responsive operations for the final delivery.

The DAM would also provide metadata such as crop coordinates, alt texts, etc. through sidecar files or an API.

For an _internal DAM_, it is higly recommended to leverage [Git LFS](https://git-lfs.github.com) for all the binaries. This will keep the authoring workflows, consisting of git cloning and pushing, efficient and the entire git repo smaller. Assuming assets are stored in a subdirectory `/assets`, a `.gitattributes` file in there could enable LFS for all the binaries in it, not affecting markdown files.

# Open Questions

- What is the .md of something like an adobe.com website going to look like? Can we keep it still readable?
- How does serverless work in a request/response environment?
- How do we have to batch-up edits into commits and PRs to get to a place where it makes sense?
- Is +1m md files in a single github repo feasible?
- Do repository references work virtually tie together code and content?
- Should very small, non-extensible (think Spark Page) projects be persisted as md outside of git (probably)?
- _<add questions here>_
