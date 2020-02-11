# Project Helix Architecture

![](Helix%20Architecture.png)

## Preface

Project Helix is based on serverless micro services architecture, where each service is managed in a separate GitHub repository. Each individual service is designed to be single-purpose and can typically be understood on its own within 15 minutes to half an hour. The connections between the different services however that make up the overall architecture require some explanation.

The goal of this document is to provide this explanation and give a lay of the land of the Helix architecture.

The three main areas in Project Helix are:

- **EDGE** delivery through the Fastly Edge computing platform
- **PIPE** markdown-based content transformation into hypermedia
- **INDEX** extraction and storage of metadata in an inverted index

The architecture diagram at the beginning of this document uses the Fundamental Modeling Concepts structure chart notation.

Unless otherwise indicated, inter-service communication is based on HTTP.

## EDGE

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

An essential technology used in the Fastly edge platform is Edge Side Includes (ESI), which enables the composition of larger responses from various constituent parts that will be assembled on the fly.

## PIPE

The Helix Pipeline is the most prominent part of the Helix architecture when it comes to extensibility of the platform and the ability of developers to customize the experiences they are creating for their visitors.

The CDN is never accessing the pipeline directly, these requests are mediated through the [`helix-dispatch`](https://github.com/adobe/helix-service). Dispatch will make multiple concurrent, speculative requests, some to Adobe I/O Runtime actions that render content dynamically, some to the [`helix-static`](https://github.com/adobe/helix-static) service, which retrieves content straight from the appropriate Git repository.

Before making any requests to either pipeline or static actions, [`helix-dispatch`](https://github.com/adobe/helix-dispatch) is using the [`helix-resolve-git-ref`](https://github.com/adobe/helix-resolve-git-ref) service to turn branch  names like `master` or `publish` into Git SHAs that offer superior caching due to their immutable nature.

The [`helix-static`](https://github.com/adobe/helix-static) action takes care of the delivery of static files and includes some additional features such as:
- handling of large files that exceed the OpenWhisk response size limit of 1 MB
- rewriting of JavaScript module and CSS files to enhance cachability
- rewriting of CSS for Font-loading to enforce Helix's single-origin policy

## INDEX

TBD