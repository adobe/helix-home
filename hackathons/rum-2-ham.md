# Rumathon II

September 16th to 20th, Hamburg, Germany (Adobe Office)

---

(See the [Hackathon Archive](./README.md) for past Operations Workshops and Hackathons)

### Rumathon???

After the [first spontaneous rumathon](./rum-1-bsl.md), we decided that we'll need a bit more of a heads up for the second iteration, so that a larger part of the team can
participate

### Agenda

> I hear this is an rumathon workshop, are you going to sample RUM all day?

Trying to get as much of the virtual team together, the goal is to have high-bandwidth exchanges on the current state and next steps of RUM. We will try to keep it hands-on, so instead
of presentations, we will rely on code settling arguments, this means we won't be hacking all the time, there will also be plenty
of programming and coding.

The main purpose of the hackathon is social: allow the team to reconnect, meet new team members for the first time, and work on
stuff that won't fit neatly into a 45-minute Teams meeting.

|      Time | Monday (Alster)                                        | Tuesday (Weser) | Wednesday (Spree) | Thursday (Spree) | Friday (Spree) |
| --------: | ------------------------------------------------------ | ------- | --------- | -------- | ------- |
|   Morning | Noon: Kickoff, Introductions, Architecture             | Hacking | Hacking   | Hacking  | Demos   |
| Afternoon | Hacking                                                | Hacking | Hacking   | Hacking  | -       |
|   Evening | Social                                                 | Social  | Social    | Social   | -       |

### Location

> Where is this going to happen? Do you have a windowless conference room blocked out?

In the Hamburg office. We don't have a room yet, but it will likely have windows.

#### Travel

Fly into Germany, then take a train to Hamburg or fly directly to HAM.

##### Accomodiation

Navan has the wrong pin for the Hamburg office, so distances may be off

- the closest Hotel (with availabilty in Navan) is [IntercityHotel Hamburg-Altona](https://hrewards.com/de/intercityhotel-hamburg-altona) – it's a 25 minute walk. If you like Deutsche Bahn, you'll feel right home at IntercityHotel
- a nice hotel that I've been staying at (and will stay) is [25hours Altes Hafenamt](https://www.25hours-hotels.com/hotels/hamburg/altes-hafenamt) (also on Navan). It's a 50 minute walk, or 25 minute walk plus ferry ride

### Goals

> What are you planning to show at the end of the week?

Put down the topic and the people that would like to discuss it:

The theme of this offsite is ....

#### Ideas / Suggestions / Topics Collection

- user engagement related
  1. `scroll` checkpoint to measure the scroll depth for the sites/pages where CTR/CVR is not relevant (ie blog/news pages)
  2. engagement-aware active `time-on-page` calculation where only the time during which the user is actively viewing or interacting with the page is counted
- traffic acquisition related
  1. analysis of existing query params to refine the categorization we have today and improve if necessary
  2. bounce rate and time-on-page by source
  3. cost per acquisition (CPA) investigation
- RUM data consumers
  1. serving Adobe's massive appetite for data research, and how that intersects with the [principles](https://github.com/adobe/helix-rum-js/blob/main/vision.md)
  2. we should have some input from GTM team about RUM by the time of the workshop, let's discuss what it means
- Domain to Customer / IMS Org ID mapping
As of today we are unable to map customer-managed CDN domains to Edge delivery services licenses. As a result, we can’t identify customers using EDS with their own CDN, which prevents us from linking them to business contracts, or collecting billing information, eg tracking content requests.
Let's discuss how we could address this mapping short-term and also let's think about a long-term solution, ideally building an automated process in future
- How to handle customers with Content Security Policy (CSP) and how can we enable Sub-resource integrity (SRI) - mostly for Cloud Service

### Attendees

> Who is going to be there? Can I come?

If you had your hands on RUM code in the past year, you are welcome to join us. Put your name on the list, so that planning is a bit easier

1. @trieloff
2. @langswei
3. @cziegeler (Mon - Thu)
4. @chicharr
5. @ekremney
6. @maxakuru
7. @phornig
8. @kptdobe (Mon - Thu)
9. @bosschaert
10. @karlpauls
11. @puric

### Preparation

> What can I do to prepare for the Hackathon?

1. Join `#rum-explorers` on Slack

### Demos
