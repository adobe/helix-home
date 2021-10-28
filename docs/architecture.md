# Project Helix Architecture



## Architectural Evolution

Helix has undergone some architectural evolution since the start of the project. The main phases are:

1. Helix 1 – a bring-your-own (Adobe I/O) runtime framework-driven approach that would allow server-side customization
2. [Helix 2](#helix-2) – a software as a service that relies on client-side customization
3. [Helix 3](#helix-3) – a software as a service with superior availability and performance with the same feature set as Helix 2

The current version of the architecture is Helix 3, prior versions stay documented as long as they are in active use.

## Helix 3

Helix 3 delivers the core feature set of Helix (high-performance websites with low code and zero-friction authoring) in a simplified architecture that optimizes stability and availability.

![](./Helix%20Architecture/Helix%203%20Stack.png)

### Preface

Project Helix is based on serverless micro services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Helix architecture.

The four main areas in Project Helix are:

- **EDGE** delivery through the Fastly Edge computing platform
- **PIPE** markdown-based content transformation into hypermedia
- **AUTHOR** authoring of content using Google Docs and Word Online, and publishing into high-availability content stores
- **DEV** development workflow

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTP.

### EDGE

A typical Helix Pages production deployment uses two stacked Fastly Service Configurations, with each Service Config using a performance-enhancing technique called shielding. 

Shielding means that all traffic from an edge node in Fastly's CDN will be forwarded to a designated "shield" node that then makes requests to the origin or backend server. This increases the likelihood of content already being cached in the shield node and reduces the overall number of requests on the origin. The shield node is simply another edge node that has been picked for reasons of proximity to the origin.

In the architecture chart above, the two CDN configurations are referred to as "outer CDN" (receives incoming requests first, serves a custom domain name, adds custom caching rules) and "inner CDN" (provides basic caching, routes requests to the various backends used by Project Helix)

The CDN configuration is managed as a VCL-based Fastly service configuration (although experimental implementations based on Cloudflare Workers and Fastly Compute@Edge exist).

Based on the URL, the Fastly Service performs an edge dictionary lookup to determine the appropriate Content Bus ID (a unique identifier of a content source used while publishing) and the type of request to handle.

- Media Requests (images, videos, etc.) are served directly from the Media Bus using content-addressable storage, where the content hash forms the significant part of the URL
- Content Requests (HTML and JSON) are served using the Helix Pipeline Service, see the [PIPE](#pipe) section for details
- Static Content is served from the Content Bus directly, or if unavailable, from the Code Bus storage location.

At the Inner CDN level, no caching is applied, so that all requests accurately represent the latest state of published content.

### PIPE

The main delivery functionality is provided by the [`helix-pipeline-service`](https://github.com/adobe/helix-pipeline-service), which uses the [`helix-pipeline`](https://github.com/adobe/helix-pipeline) framework to render HTML, and renders filtered JSON directly (after applying some filters).

The Pipeline Service pulls configuration from the Code Bus and the published Content from the Content Bus.

### AUTHOR

Authoring in Helix 3 is handled through the interplay of three components:

1. The [Helix Bot](https://github.com/adobe/helix-bot) watches a GitHub repository for code changes and invokes Helix Admin
2. The [Helix Sidekick](https://github.com/adobe/helix-sidekick) allows authors to trigger publishing actions explicitly and invokes Helix Admin
3. [Helix Admin](https://github.com/adobe/helix-admin) does all the work, with the help of [Helix Content Proxy](https://github.com/adobe/helix-content-proxy), which renders content from Sharepoint and Google Drive by invoking [Helix Data Embed](https://github.com/adobe/helix-data-embed), [Helix Word Markdown Adapter](https://github.com/adobe/helix-word2md) or [Helix Google Docs Markdown Adapter](https://github.com/adobe/helix-gdoc2md)

The [Helix Admin REST API](https://opensource.adobe.com/helix-home/admin/) is fully documented and covers the main functionality:

- Preview: generates a Markdown document from the original content source, places it in the `preview` partition of the content bus and returns the preview URL
- Publish: copies a Markdown document from the `preview` partition into the `live` partition of the content bus
- Code: copies code from the GitHub repository into the Code Bus storage, also updates the Inner CDN edge dictionaries according to the `fstab` mappings
- Cache: clears the Outer CDN cache

### DEV

Other than in Helix 2, the Helix CLI does not simulate a full blows Helix setup, but simply proxies content and media requests to the inner CDN. This allows developers to see their code changes in real-time (served from the working copy), but mix it with real content from Content and Media Bus.

## Helix 2

Helix 2 was introduced as "Helix Pages", a simple, opinionated configuration of a Helix 1 deployment that could be used as a SaaS which would allow client-side customization.

[Helix 3](#helix-3) is an evolution that simplifies the architecture to increase the overall performance and availability of the service. Helix 2 will be phased out over the coming months.

![](./Helix%20Architecture/Helix%202%20Stack.png)

The Helix 2 architecture is annotated when significant changes have been made in Helix 3. The annotations explain why the changes have been necessary.

### Preface

Project Helix is based on serverless micro services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Helix architecture.

The three main areas in Project Helix are:

- **EDGE** delivery through the Fastly Edge computing platform
- **PIPE** markdown-based content transformation into hypermedia
- **INDEX** extraction and storage of metadata in an inverted index

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTP.

### EDGE

A typical Helix Pages production deployment uses two stacked Fastly Service Configurations, with each Service Config using a performance-enhancing technique called shielding. 

Shielding means that all traffic from an edge node in Fastly's CDN will be forwarded to a designated "shield" node that then makes requests to the origin or backend server. This increases the likelihood of content already being cached in the shield node and reduces the overall number of requests on the origin. The shield node is simply another edge node that has been picked for reasons of proximity to the origin.

In the architecture chart above, the two CDN configurations are referred to as "outer CDN" (receives incoming requests first, serves a custom domain name, adds custom caching rules) and "inner CDN" (provides basic caching, routes requests to the various backends used by Project Helix)

The inner CDN configuration is assembled dynamically by the [`helix-publish`](https://github.com/adobe/helix-publish) service, which reads the Helix configuration provided by the Helix CLI, combines Helix-specific boilerplate VCL (Fastly's domain-specific language for CDN configurations) with VCL generated from the Helix configuration and the Fastly API to assemble a complex CDN configuration that is able to handle all types of requests served by Project Helix. This includes:

- Dispatch requests: for static or dynamic content
- Direct static delivery: from GitHub and Azure Blob Store
- Proxy requests: to legacy domains during content migration
- Query requests: to the Algolia query API
- Embed requests: for embedding content from other websites
- CGI-BIN requests: for calling cgi-bin-like web actions

The [`helix-publish`](https://github.com/adobe/helix-publish)  repository provides additional detail on the various request types handled by a typical CDN configuration.

As a side-note, [`helix-publish`](https://github.com/adobe/helix-publish) is not the only service that modifies the CDN configuration. There is also [`helix-logging`](https://github.com/adobe/helix-logging) which sets up request and access log forwarding to Google BigQuery and [`helix-bot`](https://github.com/adobe-private/helix-bot), which selectively invalidates the CDN cache upon content modification.

*Note: In Helix 3, there is no longer a need for `helix-publish` as only one CDN configuration exists for the entire service, making the parametrization available through `helix-publish` obsolete.*

An essential technology used in the Fastly edge platform is Edge Side Includes (ESI), which enables the composition of larger responses from various constituent parts that will be assembled on the fly.

*Note: In Helix 3, ESI is no longer used as the implementation provided by Fastly prove to be too unreliable to use at scale.*

### PIPE

The Helix Pipeline is the most prominent part of the Helix architecture when it comes to extensibility of the platform and the ability of developers to customize the experiences they are creating for their visitors.

The CDN is never accessing the pipeline directly, these requests are mediated through the [`helix-dispatch`](https://github.com/adobe/helix-service). Dispatch will make multiple concurrent, speculative requests, some to Adobe I/O Runtime actions that render content dynamically, some to the [`helix-static`](https://github.com/adobe/helix-static) service, which retrieves content straight from the appropriate Git repository.

*Note: In Helix 3, the complexity of request processing has been simplified so far that it can be implemented entirely in the Edge layer, eliminating the need for `helix-dispatch` and `helix-static`*

Before making any requests to either pipeline or static actions, [`helix-dispatch`](https://github.com/adobe/helix-dispatch) is using the [`helix-resolve-git-ref`](https://github.com/adobe/helix-resolve-git-ref) service to turn branch  names like `master` or `publish` into Git SHAs that offer superior caching due to their immutable nature.

*Note: Helix 3 uses S3 as high-performance and high-availability stores of code (Code Bus) and content (Content Bus) and use an explicit publishing model through the Helix Sidekick and Helix Admin. This makes the `helix-resolve-git-ref` service obsolete.*

The [`helix-static`](https://github.com/adobe/helix-static) action takes care of the delivery of static files and includes some additional features such as:
- handling of large files that exceed the OpenWhisk response size limit of 1 MB
- rewriting of JavaScript module and CSS files to enhance cachability
- rewriting of CSS for Font-loading to enforce Helix's single-origin policy

*Note: None of these features proved essential to achieving consistent lighthouse scores of 100, and have been dropped in Helix 3.*

### INDEX

See [indexing.md](indexing.md)
