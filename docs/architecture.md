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

#### EDGE on AWS/Fastly

#### PIPE on AWS/Fastly

### Cloudflare Delivery Stack

The Cloudflare Delivery Stack is the secondary delivery stack for Franklin 4. It is responsible for delivering the content to the end user.

![](./Franklin%20Architecture/Franklin%204%20Stack%20(Cloudflare).png)

#### EDGE on Cloudflare

#### PIPE on Cloudflare

### Authoring Stack

### Development Stack