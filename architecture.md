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
