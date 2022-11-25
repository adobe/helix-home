# Franklin Architecture

## Architectural Evolution

Franklin has undergone some architectural evolution since the start of the project. The main phases are:

1. Helix 1 – a bring-your-own (Adobe I/O) runtime framework-driven approach that would allow server-side customization
2. [Helix 2](architecture-v2.md) – a software as a service that relies on client-side customization
3. [Helix/Franklin 3](architecture-v3.md) – a software as a service with superior availability and performance with the same feature set as Helix 2
4. [Franklin 4](#overview) - a dual-stack architecture that refines Franklin 3 for greater performance and availability

You can follow the links above to learn more about the evolution of the architecture.

## Overview

Franklin 4 delivers the core feature set of Franklin (high-performance websites with low code and zero-friction authoring) in a simplified architecture that optimizes stability and availability.

The fundamental difference to Franklin 3 is that in addition to running the Franklin delivery Stack on AWS (for storage and compute) and Fastly (for routing and delivery), a second delivery Stack running on Cloudflare has been added. 

The details of these two delivery Stacks are described in these sections:

- [AWS Delivery Stack](#aws-delivery-stack)
- [Cloudflare Delivery Stack](#cloudflare-delivery-stack)

The authoring stack in Franklin 4 runs entirely on AWS, and will be descibed as part of the [Authoring Stack](#authoring-stack) section.

### Preface

Franklin is based on a serverless micro-services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Franklin architecture.

Other than most architecture documentation, this one is not a statement of intent, but rather a description of the architecture at a certain point in time, the [November 2022 ~~Barcelona~~ Distributed Hackathon](../hackathons/10-bcn.md).

The four main areas in Project Franklin are:

- **EDGE** delivery through an edge computing platform or CDN
- **PIPE** markdown-based content transformation into hypermedia
- **AUTHOR** authoring of content using Google Docs and Word Online, and publishing into high-availability content stores
- **DEV** development workflow

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTPS.

### AWS Delivery Stack

The AWS Delivery Stack is the primary delivery stack for Franklin 4. It is responsible for delivering the content to the end user. At this time, the majority of Franklin sites are delivered through this stack.

![](./Franklin%20Architecture/Franklin%204%20Stack%20(AWS).png)

#### EDGE on Fastly

A typical Franklin site uses three levels of CDN. The first level is the customer's own CDN (we refer to it as BYOCDN). This CDN caches contents delivered by the next level. We rely on long-lived shared caches with precise invalidation, which is triggered by `helix-admin` based on customer-provided configuration.

The second level is Fastly, we refer to it as the Outer CDN (even if it is in the middle of three layers of CDN). Here all pages on `*.hlx.live` are served and cached with long-lived shared caches. Whenever content gets published, the `helix-admin` service will surgically invalidate the cache for the affected pages and resources.

The outer CDN uses internally a multi-layer caching technique called shielding, which reduces load on the next layer.

The innermost CDN layer is the aptly named inner CDN, which serves `*.hlx.page` and does not cache at all. This allows authors to preview their changes without having to wait for the cache to expire or purges to go through.

The inner CDN employs an edge dictionary that maps between `owner`, `repo` and contentBusID as well as another dictionary that maps between `owner`, `repo` and the selected Franklin pipeline version (to allow for gradual rollouts of new pipeline versions).

URL pattern analysis is used to route requests to the correct request type, in particular:

- Media Requests (images, videos, etc.) are served directly from the Media Bus using content-addressable storage, where the content hash forms the significant part of the URL
- Content Requests (HTML and JSON) are served using the Franklin Pipeline Service, see the [PIPE](#pipe) section for details
- Static Content is served from the Content Bus or Code Bus directly

#### PIPE on AWS

The main delivery functionality is provided by the [`helix-pipeline-service`](https://github.com/adobe/helix-pipeline-service), which uses the [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) framework to render HTML, and renders filtered JSON directly (after applying some filters).

The Pipeline Service pulls configuration from the Code Bus and the published Content from the Content Bus.

The pipeline service runs as an AWS Lambda function and has four main components:
- rendering full page 
- and `.plain.html` HTML pages
- rendering filtered JSON from spreadsheets
- handling form submissions and stashing them in the form processing queue

The pipeline service on AWS shares key parts of the implementation with the [pipeline worker on Cloudflare](#pipe-on-cloudflare). This common functionality is provided by the [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) library.

All content that is delivered through the pipeline and some content that is served straight from the CDN are stored in Franklin Content Hub on S3. For the structure of the content hub, see the [Content Hub](#content-hub) section below.
### Cloudflare Delivery Stack

The Cloudflare Delivery Stack is the secondary delivery stack for Franklin 4. It is responsible for delivering the content to the end user.

![](./Franklin%20Architecture/Franklin%204%20Stack%20(Cloudflare).png)

The biggest architectural difference is that in Cloudflare, the inner CDN and and the pipeline are combined in a single service that is using a Cloudflare Worker.
#### EDGE on Cloudflare

Like on Fastly, the customer can use their own CDN, and opt-in selectively by using `*.hlx-secondary.live` as the origin.

The inner CDN on Cloudflare uses Worker KV instead of edge dictionaries.

Serving media is almost the same as on Fastly, the differences in the implementation of the image optimization features have been abstracted away.

Serving static content is the same as on Fastly.

The code for the outer CDN on Cloudflare is in https://github.com/adobe/helix-cloudflare-live

#### PIPE on Cloudflare

The code for the inner CDN on Cloudflare as well as the worker code for the pipeline on Cloudflare is in https://github.com/adobe/helix-cloudflare-page

All access to the Content Hub is using R2.
### Content Hub

The Content Hub is a content store optimized for delivery that uses Amazon S3 and Cloudflare R2 (which share an API). It is made up of following constituent parts:

- Media Bus (content addressable storage for images, videos and other media that can be served directly from the CDN with minimal processing)
  - within the media bus, there is one folder for each `contentBusID`, so that we can delete all content for a given customer when requested
    - within each `contentBusID` folder, files are addressed by a content hash, so that duplicate content is not stored twice within a given `contentBusID` folder
- Content Bus (path addressable storage for structured and semistructured authored content)
  - within the content bus, there is one folder for each `contentBusID`, so that we can delete all content for a given customer when requested
    - within each `contentBusID` folder, there are two content bus partitions: `preview` and `live`. These content bus partitions separate content that can be served only on `*.hlx.page` from content that is published on `*.hlx.live`. The actual process of publishing involves copying content from `preview` to `live` and purging the attached caches.
      - each content bus partition has folders that reflect the folder structure of the source repository (Sharepoint or Google Drive)
        - within each folder, there is a Markdown document for each published document and a JSON file for each published spreadsheet
        - documents can be marked with redirect metadata, in which case the pipeline will serve a redirect instead of the requested document
- Code Bus (`owner`, `repo`, `ref`, and path addressable storage for code and configuration that needs to be served as part of the site)
  - one folder for each `owner`, i.e. GitHub username or organization that has a Franklin Github bot installed
    - one folder for each `repo` (repository)
      - one folder for each `ref` (branch or tag)
        - one folder for each folder within the repository
          - one file for each file
        - auto-aggregated configuration in JSON format (so that the pipeline can read it without having to fetch multiple files and parse YAML, which blows up the bundle size)

The clear structure of the Content Hub makes it possible to keep the consuming services small and simple.
### Authoring Stack

Authoring in Franklin is handled through the interplay of three components:

1. The [Franklin Bot](https://github.com/adobe/helix-bot) watches a GitHub repository for code changes and invokes Franklin Admin
2. The [Franklin Sidekick](https://github.com/adobe/helix-sidekick) allows authors to trigger publishing actions explicitly and invokes Franklin Admin
3. [Franklin Admin Service](https://github.com/adobe/helix-admin) does all the work which renders content from Sharepoint and Google Drive by invoking [Franklin Word Markdown Adapter](https://github.com/adobe/helix-word2md) or [Franklin Google Docs Markdown Adapter](https://github.com/adobe/helix-gdoc2md)

The [Franklin Admin REST API](https://www.hlx.live/docs/admin.html) is fully documented and covers the main functionality:

- Preview: generates a Markdown document from the original content source, places it in the `preview` partition of the content bus and returns the preview URL
- Publish: copies a Markdown document from the `preview` partition into the `live` partition of the content bus
- Code: copies code from the GitHub repository into the Code Bus storage
- Cache: clears the Outer CDN cache
- Index: triggers a reindexing of the content bus, see [indexing.md](detailed description of the indexing process)

### Development Stack

Other than in Helix 2, the Franklin CLI does not simulate a full blown Franklin setup, but simply proxies content and media requests to the inner CDN. This allows developers to see their code changes in real-time (served from the working copy), but mix it with real content from Content and Media Bus.