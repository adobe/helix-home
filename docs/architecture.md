# Helix Architecture

## Architectural Evolution

Helix[^1] has undergone some architectural evolution since the start of the project. The main phases are:

1. Helix 1 – a bring-your-own (Adobe I/O) runtime framework-driven approach that would allow server-side customization
2. [Helix 2](architecture-v2.md) – a software as a service that relies on client-side customization
3. [Helix/Franklin 3](architecture-v3.md) – a software as a service with superior availability and performance with the same feature set as Helix 2
4. [Franklin 4](architecture-v4.md#overview) - a dual-stack architecture that refines Franklin 3 for greater performance and availability
5. [Helix 5](#overview) – a refactoring of the CDN/delivery stack for greater robustness and maintainability

You can follow the links above to learn more about the evolution of the architecture.

## Overview

Helix 5 delivers the core feature set of Franklin 4 (dual-stack architecture with great performance and availability) with a refactored (Fastly) CDN Stack to enable easier maintainability and reduce overall complexity.

The key differences to Franklin 4 are:
1. DNS is tracked as an explicit architectural component, and managed redundantly
2. RUM collection uses no longer a separate host, but is integrated into the CDN stack
3. The (Fastly) CDN stack is refactored to use a single Fastly service configuration instead of two stacked configurations
4. Each underlying CDN functionality (RUM, Media, Configuration, Pipeline, Static, and Forms) is implemented through a separate, simpler service
5. A new ConfigBus section of the ContentHub is created to store customer- and site-level configuration
6. The ConfigBus is joined by a Config Service that resolves and merges configuration from multiple sources
7. The Forms Collector Service is no longer part of the Pipeline Service, but a separate service
8. Inner and Outer CDN no longer log to Google BigQuery
9. The Run Query Service will be made available as part of the Admin API
10. The `hlx.page` and `hlx.live` domains will be replaced by `aem.page` and `aem.live` domains
11. (GitHub) `owner` and `repo` as the overarching identifiers for a site are replaced by (AEM) `org` and `site`
12. New sites will be able to start without a GitHub repository (repoless) by pointing their configuration to an existing repository

Until all customer projects are fully migrated, Franklin 4 and Helix 5 will be operated in parallel. New sites will default to Helix 5. Franklin 4's end of service is set to December 18, 2025.

The details of these two delivery Stacks are described in these sections:

- [AWS Delivery Stack](#aws-delivery-stack)
- [Cloudflare Delivery Stack](#cloudflare-delivery-stack)

The authoring stack in Helix 5 runs entirely on AWS, and will be descibed as part of the [Authoring Stack](#authoring-stack) section.

### Preface

Helix is based on a serverless micro-services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Helix architecture.

The four main areas in Project Franklin are:

- **EDGE** delivery through an edge computing platform or CDN
- **PIPE** markdown-based content transformation into hypermedia
- **AUTHOR** authoring of content using Google Docs and Word Online, and publishing into high-availability content stores
- **DEV** development workflow

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTPS.

### AWS Delivery Stack

The AWS Delivery Stack is the primary delivery stack for Helix 5. It is responsible for delivering the content to the end user.

![](./Franklin%20Architecture/Helix%205%20Stack%20(AWS).png)

#### EDGE on Fastly

A typical Franklin site uses three levels of CDN. The first level is the customer's own CDN (we refer to it as BYOCDN). This CDN caches contents delivered by the next level. We rely on long-lived shared caches with precise invalidation, which is triggered by `helix-admin` based on customer-provided configuration.

The second level is Fastly, we refer to it as the Outer CDN (even if it is in the middle of three layers of CDN). Here all pages on `*.aem.live` are served and cached with long-lived shared caches. In addition `*.aem.page` is served by the outer CDN, albeit with shorter cache TTLs. Whenever content gets published, the `helix-admin` service will surgically invalidate the cache for the affected pages and resources.

The outer CDN uses internally a multi-layer caching technique called shielding, which reduces load on the next layer.

The innermost CDN layer are a number of task-specific CDN services, each with a separate domain and scope.

| Name     | Domain                     | Description                                                                                                                                   |
| -------- | -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| RUM      | `rum.aem-fastly.page`      | The Helix RUM collector forwards RUM data to our logging services                                                                             |
| Media    | `media.aem-fastly.page`    | The Helix Media CDN serves images and video files from the Media Bus. It applies resizing and re-encoding using Fastly Image Optimizer        |
| Config   | `config.aem-fastly.page`   | The Helix Config CDN serves effective configurations from the Config Bus. Properties that are relevant for routing are served as HTTP headers |
| Pipeline | `pipeline.aem-fastly.page` | The Helix Pipeline CDN serves HTML and JSON content from the Pipeline Service                                                                 |
| Static   | `static.aem-fastly.page`   | The Helix Static CDN serves static content from the Code Bus and Content Bus                                                                  |
| Forms    | `forms.aem-fastly.page`    | The Helix Forms CDN receives form submissions and forwards them to the Forms Collector Service                                                |

URL pattern analysis is used to route requests to the correct request type, in particular:

- Media Requests (images, videos, etc.) are served directly from the Media Bus using content-addressable storage, where the content hash forms the significant part of the URL
- Content Requests (HTML and JSON) are served using the Pipeline Service, see the [PIPE](#pipe) section for details
- Static Content is served from the Content Bus or Code Bus directly

#### PIPE on AWS

The key components of the PIPE zone are the Config Service, the Pipeline Service, the Forms Collector Service, and the Content Hub on S3 as storage.

##### Config Service

The Config Service is a new service in Helix 5. It is responsible for resolving and merging configuration from multiple sources, in particular the `site`-specific configuration and the `org`-wide configuration. With this, `site`-configuration can override `org`-wide defaults.
The Config Service is also responsible for resolving the `owner` and `repo` of a site, which is needed to serve the correct static files from Code Bus.
Finally, the Config Service is responsible for resolving the correct `contentBusID` for a given site, which is needed to serve the correct content from Content Bus.

##### Pipeline Service

The main delivery functionality is provided by the [`helix-pipeline-service`](https://github.com/adobe/helix-pipeline-service), which uses the [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) framework to render HTML, and renders filtered JSON directly (after applying some filters).

The Pipeline Service pulls configuration from the Config Bus and the published Content from the Content Bus.

The pipeline service runs as an AWS Lambda function and has these main components:
- rendering full page
- and `.plain.html` HTML pages
- rendering filtered JSON from spreadsheets
- handling `OPTIONS` requests
- serve redirects

The pipeline service on AWS shares key parts of the implementation with the [pipeline worker on Cloudflare](#pipe-on-cloudflare). This common functionality is provided by the [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) library.

All content that is delivered through the pipeline and some content that is served straight from the CDN are stored in Franklin Content Hub on S3. For the structure of the content hub, see the [Content Hub](#content-hub) section below.

##### Forms Collector Service

The Forms Collector Service is a new service in Helix 5. It is responsible for receiving form submissions from the Forms CDN and forwarding them to the Forms Processing Queue.

#### OVERSIGHT on Fastly

A small subset of requests made to the site are sampled using Real User Monitoring (RUM). RUM captures non PII-sensitive request data and stores these for later analysis.

For 1% of requests, the browser client sends RUM data using the rum-enhancer client to the rum-collector. The collector runs in an Edge worker and forwards, after some
processing, the data to Google BigData, Coralogix and to S3 buckets. Each back-end has a different use-case for the data.

Raw Data from S3 is processed using the Rum Bundler and stored in bundles in S3 again, this prepares the data for the Rum Explorer which cruches this data and presents it in
a Web UI.

### Cloudflare Delivery Stack

The Cloudflare Delivery Stack is the secondary delivery stack for Helix 5. It is responsible for delivering the content to the end user.

As the main focus of the Helix 5 architecture is the Fastly/AWS Stack, we have not fully modeled the setup, so please refer to this earlier architecture diagram for the details:

![](./Franklin%20Architecture/Franklin%204%20Stack%20(Cloudflare).png)

The biggest architectural difference is that in Cloudflare, the inner CDN and and the pipeline are combined in a single service that is using a Cloudflare Worker.


#### EDGE on Cloudflare

Like on Fastly, the customer can use their own CDN, and opt-in selectively by using `*.aem-secondary.live` as the origin.

The inner CDN on Cloudflare consists of workers for:
- RUM
- Media
- Config, which includes the functionality of the Config Service
- Pipeline
- Static
- Forms

As with `helix-html-pipeline`, large parts of the Config Service implementation will be shared between Lambda and Cloudflare Workers.

Serving media is almost the same as on Fastly, the differences in the implementation of the image optimization features have been abstracted away.

Serving static content is the same as on Fastly.

The code for the outer CDN on Cloudflare is in https://github.com/adobe/helix-cloudflare-live

#### PIPE on Cloudflare

The code for the inner CDN on Cloudflare as well as the worker code for the pipeline on Cloudflare is in https://github.com/adobe/helix-cloudflare-page

All access to the Content Hub is using R2.

### Content Hub

The Content Hub is a content store optimized for delivery that uses Amazon S3 and Cloudflare R2 (which share an API). It is made up of following constituent parts:

- Config Bus (configuration for each `org` and `site`)
  - within the Config Bus, there is one folder for each `org`, which can hold configuration files as JSON
    - within each `org` folder, there is a separate folder for each `site`, holding configuration files as JSON. The `site` folder can override configuration from the `org` folder. The JSON configuration at least contains the `contentBusID` for the site, which is used to determine the correct content bus partition to serve content from and the GitHub `owner` and `repo` to serve static content from.
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
2. The [AEM Sidekick](https://github.com/adobe/helix-sidekick) allows authors to trigger publishing actions explicitly and invokes Franklin Admin
3. [Admin Service](https://github.com/adobe/helix-admin) does all the work which renders content from Sharepoint and Google Drive by invoking [Word Markdown Adapter](https://github.com/adobe/helix-word2md) or [Google Docs Markdown Adapter](https://github.com/adobe/helix-gdoc2md)

The [Admin REST API](https://www.hlx.live/docs/admin.html) is fully documented and covers the main functionality:

- Preview: generates a Markdown document from the original content source, places it in the `preview` partition of the content bus and returns the preview URL
- Publish: copies a Markdown document from the `preview` partition into the `live` partition of the content bus
- Code: copies code from the GitHub repository into the Code Bus storage
- Cache: clears the Outer CDN cache
- Index: triggers a reindexing of the content bus, see [indexing.md](detailed description of the indexing process)
- Query: runs a query using the Helix Run Query Service

#### HTML Provider

In addition to enabling authoring content in Microsoft Word/Excel and Google Docs/Sheets, Helix 5 incorporates the ability to ingest content from any HTML provider, as long as the HTML provided is semantically structured and uses the same HTML tags as the HTML generated by the pipeline.

This functionality has been used so far to enable authoring from:
- Adobe Experience Manager Sites as a Cloud Service
- GitHub (Markdown)
- GitHub (Asciidoc)

Other content sources can be added as needed.

### Development Stack

Other than in Helix 2, the Franklin CLI does not simulate a full blown Franklin setup, but simply proxies content and media requests to the inner CDN. This allows developers to see their code changes in real-time (served from the working copy), but mix it with real content from Content and Media Bus.

[^1]: This project has seen many names, including Helix, Project Helix, Project Franklin, Next Generation Composability, Success Edge, Edge Delivery Services in Adobe Experience Manager Sites as a Cloud Service with document-based editing. Since the general availability of the latter, the name Franklin is no longer used, so we refer to Helix as the architecture and Adobe Experience Manager (...) as the product.
