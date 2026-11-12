# Helix Architecture

## Architectural Evolution

Helix[^1] has undergone some architectural evolution since the start of the project. The main phases are:

1. Helix 1 – a bring-your-own (Adobe I/O) runtime framework-driven approach that would allow server-side customization
2. [Helix 2](architecture-v2.md) – a software as a service that relies on client-side customization
3. [Helix 3](architecture-v3.md) – a software as a service with superior availability and performance with the same feature set as Helix 2
4. [Helix 4](architecture-v4.md#overview) - a dual-stack architecture that refines Helix 3 for greater performance and availability
5. [Helix 5](architecture-v5.md) – a refactoring of the CDN/delivery stack for greater robustness and maintainability
6. [Helix 6](#overview) – the default architecture, extending Helix 5 with a unified API service, source content storage, and integrated Document Authoring

You can follow the links above to learn more about the evolution of the architecture. The previous architecture is preserved in the [Helix 5 architecture document](architecture-v5.md).

## Overview

Helix 6 is the default architecture for new sites. It builds on the delivery stack introduced in Helix 5 and extends the authoring stack with source document management and a new API Service endpoint.

The key changes from Helix 5 are:
1. The Helix API Service at `api.aem.live` manages the lifecycle of source content, preview and published content, code, and configuration.
2. A Source Bus on S3 stores source documents, organized by `org` and `site`, alongside the existing Media, Config, Code, and Content Buses.
3. Document Authoring provides editing, collaboration, and preview capabilities connected to the API service. Google Drive, SharePoint, and HTML providers remain supported content sources.
4. Configuration can be managed at the organization, profile, and site levels, with effective configuration resolved for delivery.

Sites continue to use `org` and `site` as their identifiers and the `aem.page` and `aem.live` domains for preview and published content. Code can come from a site's own GitHub repository or an existing shared repository.

The delivery stack is described in these sections:

- [AWS Delivery Stack](#aws-delivery-stack)
- [Cloudflare Delivery Stack](#cloudflare-delivery-stack) (earlier secondary-stack architecture)

The authoring stack in Helix 6 runs on AWS and is described in the [Authoring Stack](#authoring-stack) section.

### Preface

Helix is based on a serverless micro-services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Helix architecture.

The five main areas in Helix are:

- **EDGE** delivery through an edge computing platform or CDN
- **PIPE** markdown-based content transformation into hypermedia
- **AUTHOR** authoring of content using Google Docs and Word Online, and publishing into high-availability content stores
- **DEV** development workflow
- **OVERSIGHT** operational telemetry and analysis

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTPS.

### AWS Delivery Stack

The AWS Delivery Stack is the primary delivery stack for Helix 6. It is responsible for delivering the content to the end user.

![Helix 6 Stack (AWS)](https://github.com/adobe/helix-home/blob/main/docs/Helix%20Architecture/Helix%206%20Stack%20(AWS).png)

#### EDGE on Fastly

A typical Helix site uses three levels of CDN. The first level is the customer's own CDN (we refer to it as BYOCDN). This CDN caches contents delivered by the next level. We rely on long-lived shared caches with precise invalidation, which is triggered by the Helix API Service based on customer-provided configuration.

The second level is Fastly, we refer to it as the Outer CDN (even if it is in the middle of three layers of CDN). Here all pages on `*.aem.live` are served and cached with long-lived shared caches. In addition `*.aem.page` is served by the outer CDN, albeit with shorter cache TTLs. Whenever content gets published, the Helix API Service will surgically invalidate the cache for the affected pages and resources.

The outer CDN uses internally a multi-layer caching technique called shielding, which reduces load on the next layer.

The innermost CDN layer are a number of task-specific CDN services, each with a separate domain and scope.

| Name     | Domain                     | Description                                                                                                                                   |
| -------- | -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| RUM      | `rum.aem.page`              | The Helix RUM collector forwards RUM data to our logging services                                                                             |
| Media    | `media.aem.page`            | The Helix Media CDN serves images and video files from the Media Bus. It applies resizing and re-encoding using Fastly Image Optimizer        |
| Config   | `config.aem.page`           | The Helix Config CDN serves effective configurations from the Config Bus. Properties that are relevant for routing are served as HTTP headers |
| Pipeline | `pipeline.aem.page`         | The Helix Pipeline CDN serves HTML and JSON content from the Pipeline Service                                                                 |
| Static   | `static.aem.page`           | The Helix Static CDN serves static content from the Code Bus and Content Bus                                                                  |

URL pattern analysis is used to route requests to the correct request type, in particular:

- Media Requests (images, videos, etc.) are served directly from the Media Bus using content-addressable storage, where the content hash forms the significant part of the URL
- Content Requests (HTML and JSON) are served using the Pipeline Service, see the [PIPE](#pipe-on-aws) section for details
- Static Content is served from the Content Bus or Code Bus directly

#### PIPE on AWS

The key components of the PIPE zone are the Config Service, the Pipeline Service, and the Content Hub on S3 as storage.

##### Config Service

The Config Service resolves and merges configuration from multiple sources, including organization, profile, and site configuration. Site-specific configuration can override inherited defaults.
The Config Service is also responsible for resolving the `owner` and `repo` of a site, which is needed to serve the correct static files from Code Bus.
Finally, the Config Service is responsible for resolving the correct `contentBusID` for a given site, which is needed to serve the correct content from Content Bus.

##### Pipeline Service

The main delivery functionality is provided by the [`helix-pipeline-service`](https://github.com/adobe/helix-pipeline-service), which uses the [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) framework to render HTML, and renders filtered JSON directly (after applying some filters).

The Pipeline Service pulls configuration from the Config Bus and the published Content from the Content Bus.

The pipeline service runs as an AWS Lambda function and has these main components:
- rendering full page
- and `.plain.html` HTML pages
- rendering filtered JSON from spreadsheets
- generating sitemaps
- handling extensionless content requests
- handling `OPTIONS` requests
- serve redirects

The [`helix-html-pipeline`](https://github.com/adobe/helix-html-pipeline) library provides shared rendering functionality, also used by the earlier [pipeline worker on Cloudflare](#pipe-on-cloudflare).

All content that is delivered through the pipeline and some content that is served straight from the CDN are stored in the Content Hub on S3. For the structure of the content hub, see the [Content Hub](#content-hub) section below.

#### OVERSIGHT on Fastly

A small subset of requests made to the site are sampled using Operational Telemetry. Operational Telemetry captures non PII-sensitive request data and stores these for later analysis.

For 1% of requests, the browser client sends data using the rum-enhancer client to the rum-collector. The collector runs in an Edge worker and forwards, after some
processing, the data to Google BigData, Coralogix and to S3 buckets. Each back-end has a different use-case for the data.

Raw Data from S3 is processed using the Rum Bundler and stored in bundles in S3 again, this prepares the data for the Rum Explorer which cruches this data and presents it in
a Web UI.

The Helix 6 stack diagram also shows Outer CDN request logs flowing to Klickhaus for analysis, alongside the sampled RUM data processed through the Data Bus and RUM Bundler.

![](./Franklin%20Architecture/Operational%20Telemetry.png)

Within Operational Telemetry, four big areas are:

- `SCRIPT`: the collection scripts that are injected or loaded by the page
- `COLLECT`: the data collection edge compute infrastructure
- `SITE DOM`: the DOM of the HTML pages visited and observed
- `ANALYZE`: tools and infrastructure for analyzing the data

#### Operational Telemetry `SCRIPT`

There are two relevant projects. [`helix-rum-js`](https://github.com/adobe/helix-rum-js) is the basic collection infrastructure.
It is optimized for a minimum footprint, and performs three key tasks: first, it decides if the current page view is part of the sample, second it collects the `top` checkpoint to establish a baseline, and third it loads [`helix-rum-enhancer`](https://github.com/adobe/helix-rum-enhancer) for additional data collection. As this loading happens only for a small sample (1/100 page views), the effective size of the enhancer is a hundredth of the total size of the script.

The `helix-rum-js` scripts are provided in different formats:

- `micro.js`: is meant to be embedded into the `<head>` of the HTML page and is the smallest possible footprint
- `standalone.js`: can be embedded into core libraries like `aem.js` or loaded as a standalone script. It is being loaded conditionally by `micro.js`
- `standalone-404.js`: is a standalone script for 404 pages. It will automatically send the `404` checkpoint.

The `helix-rum-enhancer` is a modular architecture that consists of a core `index.js` file for common functionality and a set of plugins that will be loaded dynamically based on the page's content. For instance, the form plugin will be omitted on most pages, as they do not contain forms. This is a second enhancement that helps to keep the effective size of the script small.

#### Operational Telemetry `COLLECT`

The core collection infrastructure is provided by [`helix-rum-collector`](https://github.com/adobe/helix-rum-collector). It is a dual-stack (Fastly/Cloudflare) edge collection infrastructure that sanitizes the data, and forwards it into three data storage systems:
- Coralogix for operational logging
- Google BigQuery for ad-hoc analytics
- DataBus (S3) for day-to-day operational telemetry

#### Operational Telemetry `SITE DOM`

The DOM of the pages that are opened in the visitor's browser are an important part of the Operational Telemetry architecture.
Depending on the environment, different techniques are used to bootstrap the inclusion of `helix-rum-js`:

- Through `micro.js` injected into the `<head>` tag by a content delivery system like the AEM Sites publish tier
- By being embedded into a core library like `aem.js` in case of sites running on aem.live
- Or by being explicitly loaded through a `<script src>` tag

#### Operational Telemetry `ANALYZE`

Operational Telemetry data is analyzed by being pre-processed through [`helix-rum-bundler`](https://github.com/adobe/helix-rum-bundler), which aggregates raw sampled events into hourly, daily, and weekly bundles, using subsampling to normalize the amount of data served.

The bundler service is also responsible for serving completed bundles with high cache efficiency and will be called by various consumers of the telemetry data that provide insights into the changes that need to be made to the site to keep it working as intended.

### Cloudflare Delivery Stack

The Cloudflare Delivery Stack was documented as the secondary delivery stack for Helix 5. This section describes that earlier architecture; the Helix 6 diagram above models the AWS stack.

For the earlier secondary-stack design, see the [Helix 5 architecture document](architecture-v5.md#cloudflare-delivery-stack) and this diagram:

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

The Content Hub uses Amazon S3 in the Helix 6 AWS stack. The earlier Cloudflare delivery stack uses R2, which shares the S3 API. The Content Hub is made up of the following constituent parts:

- Source Bus (source documents and associated metadata)
  - source documents are organized by `org`, `site`, and document path
  - the Helix API Service provides source document operations, including reading, writing, listing, deleting, and managing versions
- Config Bus (organization, profile, and site configuration)
  - within the Config Bus, there is one folder for each `org`, which can hold configuration files as JSON
    - organization profiles provide shared configuration that sites can inherit
    - within each `org` folder, there is a separate folder for each `site`, holding configuration files as JSON. Site configuration can override inherited defaults. The effective JSON configuration contains the `contentBusID` for the site, which is used to determine the correct content bus partition to serve content from and the GitHub `owner` and `repo` to serve static content from.
- Media Bus (content addressable storage for images, videos and other media that can be served directly from the CDN with minimal processing)
  - within the media bus, there is one folder for each `contentBusID`, so that we can delete all content for a given customer when requested
    - within each `contentBusID` folder, files are addressed by a content hash, so that duplicate content is not stored twice within a given `contentBusID` folder
- Content Bus (path addressable storage for structured and semistructured authored content)
  - within the content bus, there is one folder for each `contentBusID`, so that we can delete all content for a given customer when requested
    - within each `contentBusID` folder, there are two content bus partitions: `preview` and `live`. These content bus partitions separate content that can be served only on `*.aem.page` from content that is published on `*.aem.live`. The actual process of publishing involves copying content from `preview` to `live` and purging the attached caches.
      - each content bus partition has folders that reflect the folder structure of the source repository (Sharepoint or Google Drive)
        - within each folder, there is a Markdown document for each published document and a JSON file for each published spreadsheet
        - documents can be marked with redirect metadata, in which case the pipeline will serve a redirect instead of the requested document
- Code Bus (`owner`, `repo`, `ref`, and path addressable storage for code and configuration that needs to be served as part of the site)
  - one folder for each `owner`, i.e. GitHub username or organization that has the GitHub bot installed
    - one folder for each `repo` (repository)
      - one folder for each `ref` (branch or tag)
        - one folder for each folder within the repository
          - one file for each file
        - auto-aggregated configuration in JSON format (so that the pipeline can read it without having to fetch multiple files and parse YAML, which blows up the bundle size)

The clear structure of the Content Hub makes it possible to keep the consuming services small and simple.
### Authoring Stack

Authoring in Helix 6 is handled through the interplay of these components:

1. The [GitHub Bot](https://github.com/adobe/helix-bot) watches a GitHub repository for code changes and invokes the Helix API Service to synchronize code
2. The [AEM Sidekick](https://github.com/adobe/helix-sidekick) allows authors to trigger preview and publishing actions explicitly through the Helix API Service
3. [Document Authoring](https://www.da.live/) provides editing, collaboration, and preview capabilities, with source content managed through the Helix API Service and stored in the Source Bus
4. The [Helix API Service](https://github.com/adobe/helix-api-service) manages source content, authentication, configuration, caches, content, media, publishing, preview, indexing, code synchronization, and logs
5. Content adapters convert content from SharePoint, Google Drive, and HTML providers using the [Word Markdown Adapter](https://github.com/adobe/helix-word2md), [Google Docs Markdown Adapter](https://github.com/adobe/helix-gdoc2md), and [HTML Markdown Adapter](https://github.com/adobe/helix-html2md-service)

The [Helix API](https://github.com/adobe/helix-api-service/blob/main/docs/openapi/api-service.yaml) is served at `https://api.aem.live` and covers the main functionality:

- Source: manages source documents and their versions in the Source Bus
- Config: manages organization, profile, and site configuration
- Preview: generates a Markdown document from the original content source, places it in the `preview` partition of the content bus and returns the preview URL
- Publish: copies a Markdown document from the `preview` partition into the `live` partition of the content bus
- Code: copies code from the GitHub repository into the Code Bus storage
- Cache: clears the Outer CDN cache
- Index: triggers a reindexing of the content bus; the [indexing description](helix2/indexing.md) explains the original indexing model
- Logs: provides access to operational logs

#### HTML Provider

In addition to enabling authoring content in Microsoft Word/Excel, Google Docs/Sheets, and Document Authoring, Helix 6 retains the ability to ingest content from any HTML provider, as long as the HTML provided is semantically structured and uses the same HTML tags as the HTML generated by the pipeline.

This functionality has been used so far to enable authoring from:
- Adobe Experience Manager Sites as a Cloud Service
- GitHub (Markdown)
- GitHub (Asciidoc)

Other content sources can be added as needed.

### Development Stack

The [AEM CLI](https://github.com/adobe/helix-cli) does not simulate a full Helix setup, but proxies content and media requests to the delivery stack. This allows developers to see their code changes in real-time (served from the working copy), mixed with real content from the Content and Media Buses. The Helix 6 diagram also shows locally synchronized content used by the CLI.

[^1]: This project has seen many names, including Helix, Project Helix, Next Generation Composability, Success Edge, Edge Delivery Services in Adobe Experience Manager Sites as a Cloud Service with document-based editing. We refer to Helix as the architecture and Adobe Experience Manager (...) as the product.
