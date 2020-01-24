![](https://pix10.agoda.net/hotelImages/2402734/-1/b5d74d246877c37d36189a12343f3409.jpg?s=1024x768)

# Helix Hackathon Part VIII

Feb 10-14 2020, Palm Springs, CA

---

(See the [Hackathon Archive](./README.md) for past Hackathons)

The eighth Project Helix Hackathon will happen in the week of February 10th. It is still a few days out, but as space is limited, we ask you to register as soon as possible.

### Agenda

> I hear this is a hackathon, are you going to hack all day?

We do have the venue from **Monday** to **Friday**, so if you can accommodate, I recommend to fly in on Sunday.

Although this is a hackathon, we won't be hacking all the time, there will also be plenty of programming and coding.

| Time      | Monday                     | Tuesday                          | Wednesday   | Thursday    | Friday                   |
| --------: | -------------------------- | -------------------------------- | ----------- | ----------- | ------------------------ |
|   Morning | Set-up, Airport Transfer & Arrival                          | Helix Introduction & Demo Format | Programming | Programming | Demos                    |
| Afternoon | Set-up, Coding, Airport Transfer & Arrival | Software Engineering             | Coding      | Coding      | Team lunch and departure |
|   Evening | Drinks by the pool             | Fiddling with Code               | Hacking     | Team dinner | -                        |

#### Remote Attendance

https://bluejeans.com/743383165 (only used for select sessions, see above)

### Location

> Where is this going to happen? Do you have a windowless conference room blocked out?

---

![Ace Hotel Palm Springs](https://media2.trover.com/T/4fd88b659738f037dc000045/fixedw_large_4x.jpg)

The hackathon will take place at the [Ace Hotel & Swim Club](https://www.acehotel.com/palmsprings/spa-and-swim-club/swim-club/) at [701 E Palm Canyon Dr, Palm Springs, CA](https://goo.gl/maps/mYjj9XhUn8n1RB677).

---


The [Clubhouse 1st Floor](https://www.acehotel.com/palmsprings/events-and-spaces/event-spaces/spaces/) has room for 20+ people. 

#### Accommodation

While we do have reserved 10 hotel rooms with a special group rate (169$/night + tax & fees) attendees will be responsible for booking (and paying for) their rooms. Booking instructions will follow on the `#helix-chat` Slack channel.

**Important:** The contingent of 10 rooms with the special group rate is held until **January 10th** only, so please book your room before that date.

#### Transportation

Nearest international hub airport is LAX. We recommend sharing a rental car to Palm Springs (~2hr in morning/weekend, ~3hr during afternoon/evening traffic). Pooling an Uber/Lyft from LAX is also possible but difficult due to restrictions on where they can pick up (an off-airport lot called LAX-it).

If you are flying from within the US, Palm Springs (PSP) is the closest airport, followed by Ontario (ONT, ~1hr away) which is a lot more convenient than LAX, Orange County (SNA, ~1.5-2hrs depending on traffic), or Long Beach (LGB, ~2+hrs). Note that traffic times are much worse going westbound in the morning and eastbound in the evening due to cheaper housing in the desert.

### Goal

> What are you planning to show at the end of the week?

Put down the topic and the people that would like to discuss it:

- Blog POC https://github.com/davidnuescheler/theblog/issues?q=is%3Aissue+is%3Aopen+label%3APOC
- We are looking to bring Algolia into the stack as a search service and would like to route search requests to Algolia through Fastly https://github.com/adobe/helix-index-files/issues/3
- We are eager to try out Fastly Fiddle in our CI builds for Helix Publish https://www.fastly.com/blog/testing-fastly-ci https://github.com/adobe/helix-publish
- We’ve signed up for the Compute@Edge beta and would be glad to take first steps with you – one candidate for porting is https://github.com/adobe/helix-dispatch
- I’d like to make some progress with soft purges and serving stale content: https://github.com/adobe/helix-home/issues/51 
- ESI support: we are thinking about moving ESI support from the egde into the Dispatch function (on origin) so that we can get support for alt fallbacks (but we would need to implement this ourselves, of course
- we are moving Helix Pages more and more to a multi-repo architecture and will need to serve things from multiple sources: https://github.com/adobe/helix-pages/issues/93
- I could need some advice and help on anonymizing IP address data for Helix Logging (best practices for truncating and hashing IP addresses)
- I’d like to figure out a way to secure the Helix Embed service, so that it can be called from our customer’s Fastly configs without any restrictions, but is rate limited otherwise.

### Attendees

> Who is going to be there? Can I come?

If you plan to attend the Hackathon, please add yourself to the list below and provide information regarding your room booking:

1. @stefan-guggisberg (arrival LAX Saturday 4:20pm, room booked: check-in sunday, check-out friday, departure LAX Friday 6:30pm)
1. @tripodsan (arrive LAX Sunday 9:25am, room booked: check-in sunday, check-out friday)
1. @rofe (arrival LAX Sunday 4:20pm, room booked Sunday - Friday, departure LAX Friday 7:20pm)
1. @trieloff (arrival LAX Monday 2pm, room booked: check-in monday, check-out friday)
1. @davidnuescheler (no room required)
1. @fielding (~90 miles from home, room booked: Monday-Friday, can drive 3 back to LAX if needed)
1. @marquiserosier (arrival PSP Sunday 10:13am, room booked: check-in Sunday, check-out Saturday)
1. @ejthurgo (arrival LAX Sunday 18:50pm, room booked: check-in sunday, check-out saturday)
1. @drwilco (arrival PSP Sunday 18:02pm, room in progress)
1. @ramboz (room booked: check-in sunday, check-out friday)
1. @olyver (room booked: checkin Sunday, checkout Friday)

Please share this page with people inside Adobe that you'd like to invite. Add yourself to the list if you want to attend.

### Preparation

> What can I do to prepare for the Hackathon?

1. Read the `README.md` and linked vision documents in this repo
2. Join `#helix-chat` on Slack
3. Install the `hlx` Command Line app and create your first project
4. Comment on the GitHub issues you think would be good candidates for the Hackathon

### Demos

TBD
