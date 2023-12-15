# Helix Operations Workshop XII

January 8th to 12th 2023, ACE Hotel Palm Springs, CA

---

(See the [Hackathon Archive](./README.md) for past Operations Workshops and Hackathons)

### Agenda

> I hear this is an operations workshop, are you going to operate all day?

In this operations workshop, we will discuss how we are operating our services, what changes can, should and need to be done to
improve quality of service for our customers and quality of life of our operators. We will try to keep it hands-on, so instead
of presentations, we will rely on code settling arguments, this means we won't be hacking all the time, there will also be plenty 
of programming and coding.

The main purpose of the hackathon is social: allow the team to reconnect, meet new team members for the first time, and work on 
stuff that won't fit neatly into a 45-minute Teams meeting.

| Time      | Monday                              | Tuesday           | Wednesday         | Thursday          | Friday            |
| --------: | ----------------------------------- | ----------------- | ----------------- | ----------------- | ----------------- |
|   Morning | 10:00 Architecture walkthrough | Hacking | Hacking | Hacking | Hacking  |
| Afternoon | Hacking | Hacking | Hacking | Hacking | Demos |
|   Evening | Social                          | Social        | Social        | Social        | -       |

### Location

> Where is this going to happen? Do you have a windowless conference room blocked out?

We've booked a meeting room at the ACE Hotel in Palm Springs, CA.

#### Travel

> traveling to PSP by plane...
> you can either fly directly into LAX and grab an uber/lyft which is between 2-4 hours depending on traffic, or if you have a stop over in a US city that connects to PSP, you can fly directly to PSP or alternatively fly to ONT and then ONT is more reliably 1h15 to Palm Spring Spring.

### Goals

> What are you planning to show at the end of the week?

Put down the topic and the people that would like to discuss it:

The theme of this offsite is to make considerable progress on the remaining Helix5 tasks as defined in the
architecture vision. We will align the work with the main topics

#### Track 1: Simplify CDN tier and new service architcture
- CDN Services break up
- Config Bus Service
- Config Bus Admin endpoint (versioning)
- Forms, RUM, etc.
- Auth

#### Track 2: Repoless experience
- Templates / Block Collection completion
- Creating new sites via config service (testing new config, mountpoints) 
- Import of existing site content
- Markup mountpoint as fallback for gdrive / sharepoint
- Simple Markup based OOTB mountpoint

#### Track 3: Backwards compatibility within reason, Migration is a reality
- Migration / rollout plans
- Customer communications
- Long-term backwards compatibility by component

#### Track 4: Remove all externally visible mentions of helix
- aem.live, main--website-helix--adobe.aem.page
- sidekick implications
- get outside-in, ideal project to work



#### Ideas / Suggestions / Topics Collection
- Multi Mountpoints - especially with more and more projects using multipe content sources (docs & AEM Author)
- Helix 5 repoless = no need to create a github repo to create a full site
- Demos target:
  - as a non-dev user, I import a simple site without writting a single line of code (use Sidekick default import feature - content copy /paste, default blocks ?, enhanced default block foundation, repoless...)
  - Brainstorming outcomes: how to import a complex site with the mininum of technical skills (import with blocks from block foundation, eye-dropper? (logo, fonts, styles...), templating, repoless...)
- Working / brainstorming tracks:
  - create a solid block foundation
  - default templates and customisation for repoless
  - default import with block from block foundation


### Attendees

> Who is going to be there? Can I come?

This workshop is for the Helix on-call team as well as invited black belt VIP leads and invited frequent collaborators.
If you have been invited and will come, please put your name down in the list.

1. @trieloff
1. @rofe
1. @royfielding
1. @stefan-guggisberg
1. @tripodsan
1. @dkuntze 
1. @dylandepass
1. @bstopp
1. @mhaack
1. @shsteimer
1. @chicharr
1. @davidnuescheler
1. @auniverseaway
1. @ddragosd
1. @gilliankrause
1. @3vil3mpir3
1. @synox (Aravindo Wingeier)
1. @nc-andreashaller
1. @karlpauls
1. @andreituicu
1. @catalan-adobe
1. @maxakuru
1. @ryanmparrish
1. @cazzaranjosh

### Preparation

> What can I do to prepare for the Hackathon?

1. Read the `README.md` and linked vision documents in this repo
2. Join Discord
3. Install the `aem` Command Line app and [create your first project](https://www.aem.live/tutorial)
4. Comment on the GitHub issues you think would be good candidates for the Hackathon

### Demos
