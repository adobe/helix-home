![](./2-cloudy/2.jpg)

# Project Helix Get Together/Stay Apart II

Multi Cloudy Edition — November 24, 2020, Online

---

> What's better than one big, grey cloud?

Multiple big, grey, and totally redundant clouds.

(See the [Archive](./README.md) for past Get Togethers and Stay Aparts – there aren't any, this will be the first one)

We can't have any [Hackathons](../hackathons/README.md) this year, so we get together by staying apart in a six-hour virtual hangout session to chat, code, and snack.

For the second Stay Apart, we selected last Tuesday of November because the [first Stay Apart](1-spooky.md) was on the last Tuesday of the month.

### Organization

> I hear this is a replacement for a hackathon, are you going to hack all day?

No. The main goal is to chat and socialize, any coding that will be done is a plus.

#### Date and Time

November 24th, 2020, Online

- San Jose: 4 am to 10 am
- Basel: 1 pm to 7 pm
- Tokyo: 8 pm to 2 am

#### How it Works

1. Get a reasonably quiet place
2. Get separate screens for Bluejeans and your regular desktop
3. Join the BlueJeans session
4. Leave your microphone unmuted and camera on
5. Talk to anyone at any time
6. Use a breakout Bluejeans session for discussions and list it [below](#breakouts)
7. No recordings will be made
8. If you need a break, take a break

### Location

> Where is this going to happen? Do you have a windowless conference room blocked out?

[https://bluejeans.com/344896245](https://bluejeans.com/344896245)

Remote Attendance Only

#### Breakouts

- none yet

### Goal

> What are you planning to discuss during the day?

1. Our goal is to get some parts of Helix (such as the Pipeline or resolve-git-ref) to run in as many serverless or edge compute platforms as possible. Interesting Platforms are:

- Azure Functions
- AWS Lambda
- Fastly Compute@Edge
- Cloudflare Workers
- Google Cloud Functions

2. Improve automation

_By @tripodsan_
> everytime renote creates a PR for a service, it fails to run some of the CI checks, due to the fact, that the renovate user is not authorized to access our CI credentials. To mitigate this problem, we created a github action to pushes an empty commit in order to trigger CI again with another user. So now the checks pass, but since the PR was modified, renovate will no longer auto-merge the PR. the result is, that we manually need to merge in dozens of PRs every day.  
let's quickly discuss the options and maybe hack a POC.

3. How to get execution time stable

_By @kptdobe_
> some stats I recently made reveals that the same request takes between 1.5s to more 12s (sometimes even times out). This is compeletely unpredictable and have many causes (hit sharepoint or not, some cache somewhere...). While it is fine to have 2 cases (cache vs non-cached) and some fluctuations (cold start...), I think this should be in a decent range (1 or 2s max, not 10+s) in order in a second step to be able to work on making it faster. Let's discuss our options to understand why and build a plan to solve this critical problem.

### Attendees

> Who is going to be there? Can I come?

1. @trieloff 
1. @rofe
1. @tripodsan
1. @kptdobe

Everyone is invited, attendance is not mandatory.

### Preparation

> What can I do to prepare for the Stay Apart?

1. Read the `README.md` and linked vision documents in this repo
2. Join `#helix-chat` on Slack
3. Install the `hlx` Command Line app and create your first project
4. Comment on the GitHub issues you think would be good candidates for the Hackathon

### Food & Beverages

Stock up on snacks, drinks, and order food delivery so that we can eat together, apart.
