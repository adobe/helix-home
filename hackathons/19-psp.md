# Helix Operations Workshop XIX

February 2nd to 6th 2026, Avalon Hotel Palm Springs, CA

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

|      Time | Monday                                                 | Tuesday | Wednesday | Thursday | Friday  |
| --------: | ------------------------------------------------------ | ------- | --------- | -------- | ------- |
|   Morning | 9:00 Kick-off, Goals                                   | Hacking | Hacking   | Hacking  | Hacking |
| Afternoon | Hacking                                                | Hacking | Hacking   | Hacking  | Demos   |
|   Evening | Social                                                 | Social  | Social    | Social   | -       |

### Location - NEW!

> Where is this going to happen? Do you have a windowless conference room blocked out?

The ACE was not able to accomodate our dates this year, so we're going to give Avalon Hotel Palm Springs a try. 

For room bookings, we'll need to book directly with the hotel rather than through Navan. 

Use the code ADOBE2026 in the "Group Code" field when [booking](https://www.avalon-hotel.com/palm-springs/) 

#### Travel

Palm Springs (PSP) is an international airport, with direct flights from many overseas and EMEA locations.

You can either fly directly into LAX and grab an uber/lyft which is between 2-4 hours depending on traffic, or if you have a stopover in a US city that connects to PSP, you can fly directly to PSP or alternatively fly to ONT and then ONT is more reliably 1h15 to Palm Springs.

### Goals

> What are you planning to show at the end of the week?

Put down the topic and the people that would like to discuss it:

The theme of this offsite is to be determined based on Q1 2026 priorities and customer needs.

#### Ideas / Suggestions / Topics Collection

Let's make the list of topics more of operational tasks, than garage week style explorations.

- DA collab topics [@hannessolo](https://github.com/hannessolo)
  - Release da-collab for DA sheets
  - Extend da-collab to DA configs
- Quick Edit topics [@hannessolo](https://github.com/hannessolo) [@mhaack](https://github.com/mhaack)
  - Sidekick button states -> Change Quick Edit to Close button after opening, TBD with [@rofe](https://github.com/rofe)
  - HLX 6: "no preview" aka. aem.page shows /source content directly
- DA Admin topics [@dkuntze](https://github.com/dkuntze)
  - Discuss reviving https://github.com/adobe/da-admin/issues/150 to support more than 1000 files in DA
- tools & labs cleanup (@hsteimer?)
- boilerplate cleanup (@fkakatie?)
- hlx.(page|live) sunset
- helix 6 rollout?
- BYO DNS sunset?
- plan for file-based config sunset
- CDN configuration, poor caching setup (@davidnuescheler)
- image delivery, webp & avif only? (@davidnuescheler)
- delivery markup evolution strategy (@davidnuescheler)
- clickhouse (@langswei, @trieloff)
- what about forms? (@dylandepass?)
- DA & UE future (@mhaack)
- boilerplate and AuthorKit (@auniverseaway?)
- Docs-a-thon V2
  - update docs to remove (or de-emphasize) file based config approaches
  - DA docs on aem.live?
- Media log - following items during the hackathon: (@amol-anand)
  - triggers in helix-admin to add an entry to media log when content gets published and adding deletion entries when something gets unpublished.
  - build a utility that can suck in all assets from the content of a site and add it to the media-log retroactively
  - update Kiran's media library tool to use the new log
  - any other changes needed in the format of the log entries
- Tools changes should be surfaced in Release History (couple of customers / partners asking for this - IBM most recently)
- json2html logs surfaced in audit log
      
#### Tracks

**TRACK 1 - Sunset**

- hlx.(page|live) sunset (@davidnuescheler, @stefan-guggisberg)
- plan for file-based config sunset (@davidnuescheler, @tripodsan)
- plan for forms.aem.live sunset (@dylandepass)

**TRACK 2 - Cleanup**

- CDN configuration, poor caching setup (@davidnuescheler, @stefan-guggisberg)
- tools & labs cleanup (@shsteimer)
   - finish transition from labs to tools
   - unified auth & UX?
   - move RUM explorer to tools?
- boilerplate cleanup (@fkakatie)
   - review and merge PRs
   - button decoration (aem.js)
   - delayed vs consented
- docs cleanup: remove (or de-emphasize) file based config approaches (@shsteimer)
- image delivery: webp & avif only? (@davidnuescheler)
- delivery markup evolution strategy (@davidnuescheler)

***TRACK 3 - Helix 6***

- helix 6 rollout plan
- helix 6 logging cleanup
- sync bulk jobs
- collab
   - sheets, config?
- large source lists

***TRACK 4 - misc future stuff***

- DA & UE future (@mhaack)
- boilerplate and AuthorKit (@auniverseaway?)
- clickhouse (@langswei, @trieloff)
- quick edit (@mhaack, @hannesolo, @rofe, @dylandepass)
- BYO DNS sunset? (@davidnuescheler)
- media log (@amol-anand)


### Attendees

> Who is going to be there? Can I come?

This workshop is for the Helix on-call team as well as invited black belt VIP leads and invited frequent collaborators.
If you have been invited and will come, please put your name down in the list.

1. @trieloff
2. [@stefan](https://github.com/stefan-guggisberg)
3. [@rofe](https://github.com/rofe)
4. @tripodsan
5. [@mhaack](https://github.com/mhaack)
6. @gilliankrause
7. @amol-anand
8. @maxakuru
9. [@fkakatie](https://github.com/fkakatie)
10. [@dylandepass](https://github.com/dylandepass)
11. @royfielding
12. [@dkuntze](https://github.com/dkuntze)
13. [@shsteimer](https://github.com/shsteimer)
14. [@andreituicu](https://github.com/andreituicu)
15. [@usman-khalid](https://github.com/usman-khalid)
15. @langswei
16. @cazzaranjosh
17. @cazzarankarol
18. @lkrapf
19. [@kmurugulla](https://github.com/kmurugulla)
20. [@auniverseaway](https://github.com/auniverseaway)
21. [@hannessolo](https://github.com/hannessolo)
22. [@sagarsane](https://github.com/sagarsane)
23. @davidnuescheler

### Preparation

> What can I do to prepare for the Hackathon?

1. Read the `README.md` and linked vision documents in this repo
2. Join Discord
3. Install the `aem` Command Line app and [create your first project](https://www.aem.live/tutorial)
4. Comment on the GitHub issues you think would be good candidates for the Hackathon

### Demos
