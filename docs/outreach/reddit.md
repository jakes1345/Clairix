# Reddit (r/privacy, r/degoogle)

Neither subreddit's rules could be read from here (Reddit blocks automated
access), so nothing below assumes what they allow. Find the current rules on
https://www.reddit.com/r/privacy/about/rules/ and
https://www.reddit.com/r/degoogle/about/rules/ in a logged-in browser.

## The plan

1. **Take part first.** Use your own, ordinary account. Answer questions in
   both subs about trackers, stalkerware and Android privacy for a few weeks,
   without linking Clairix. Many subs have account-age and karma limits and
   limit self-promotion; this is also simply how to be a member rather than an
   advertiser.
2. **Read each sub's rules and sidebar** for self-promotion, developer posts
   and showcase threads. r/degoogle appears to run a weekly showcase thread;
   confirm that on the rules page before using it.
3. **Ask the moderators** (Message the mods, from the sub's sidebar) whether a
   disclosed developer post is allowed, and where. Use the message below.
4. **Post only where and how they say.** One post per sub. If they say no,
   that is the answer.

Never:

- use a second account, or have anyone post "I found this great app" for you;
- ask anyone to upvote, comment or brigade;
- delete and repost to get another shot at the front page;
- leave out that you are the developer.

For r/degoogle specifically: every published edition includes Google's
`play-services-location`. That audience will notice. Say it first, and say
what happens without Play services (Android's own `LocationManager`). A build
without the library (the `foss` flavour, for F-Droid) exists in the source but
is not published anywhere yet.

## Message to the moderators

```text
Subject: Is a disclosed developer post allowed?

Hi. I'm the developer of Clairix, a free GPLv3 Android app that checks for
Bluetooth trackers, stalkerware, fake cell towers and fake Wi-Fi, with E2EE
messaging. It's a one-person project with no ads or analytics.

Would you allow one post about it, clearly marked as from the developer, or
is there a thread you'd prefer I use? If it's not a fit for the sub, that's
fine, and thanks for reading.

Source: https://github.com/jakes1345/Clairix
```

## Post, for when the moderators allow it

**Title:**

```text
[Developer] Clairix: open-source Android app that checks for trackers, stalkerware and fake cell towers. Feedback wanted
```

**Body:**

```text
I'm the developer, posting with the mods' OK.

Clairix is a free, GPLv3 Android app (12+, no root) for checking whether
something is watching you:

- Bluetooth trackers (AirTag, Tile, SmartTag, Chipolo and more), flagged as
  following only after they've been heard with you across real distance.
- Stalkerware: apps scored on the access stalkerware needs, and matched
  against the Echap stalkerware indicator list; CA certificates, proxies, USB
  debugging and device owner checked too.
- Fake cell towers (baseline of normal towers, 2G downgrades), fake Wi-Fi,
  GPS spoofing, ultrasonic beacons, and a hidden-camera sweep.
- E2EE messages and calls (vodozemac/Olm) through relays anyone can run.

The honest part:
- Beta, and not independently audited.
- Detection is best-effort; the camera sweep hasn't been validated on real
  hardware yet.
- It includes Google's play-services-location (used only where Play services
  exists), so it isn't on F-Droid.
- Much of the code was written with an AI coding assistant; the commit
  history says so.
- No analytics, ads or crash reporting, so I have no idea how many people use
  it.

Install: APK from GitHub Releases or clairix.app/download (with SHA-256), or
Obtainium with "Include prereleases" on. Not on Google Play.

Source and the full list of what it can't do:
https://github.com/jakes1345/Clairix

I'd like to hear about false positives and anything that looks wrong.
```
