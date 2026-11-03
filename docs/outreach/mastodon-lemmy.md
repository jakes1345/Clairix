# Mastodon and Lemmy

Post from your own human account, one that also takes part in other
discussion. infosec.exchange bans promo-only accounts and accounts run by an
LLM, so write and post these yourself and answer replies yourself.

## Mastodon (infosec.exchange or any instance)

Under 500 characters (469 as written). Add a content warning such as
"stalking, surveillance" if your instance expects one for the topic.

```text
I've been building Clairix, a GPLv3 Android app (12+, no root) that looks for Bluetooth trackers, stalkerware, fake cell towers, fake Wi-Fi and GPS spoofing, with E2EE messaging over relays anyone can run.

Still beta, not independently audited, best-effort detection, and much of the code was written with an AI assistant. Not on Play or F-Droid; APKs on GitHub.

Feedback on false positives very welcome.

https://github.com/jakes1345/Clairix

#Android #Privacy #FOSS
```

## Lemmy (lemmy.ml/c/privacy, and related communities)

One post, in one community at a time. Wait a couple of weeks before posting
the same thing again anywhere. Link to the GitHub repository, not a page with
trackers (the site has none, but the repository is the better link anyway).

**Title:**

```text
I made a GPLv3 Android app that checks for trackers, stalkerware and fake cell towers. Looking for feedback (developer post)
```

**Body:**

```text
Disclosure: I'm the developer. Clairix is a one-person, free and open-source
(GPLv3) project, in beta.

What it does, on a stock Android 12+ phone with no root:

- Bluetooth trackers (AirTag, Tile, SmartTag, Chipolo and others): says one is
  following you only after it has been heard at places far apart that you
  travelled between.
- Stalkerware: scores apps on the access stalkerware needs and matches them
  against the Echap stalkerware indicator list; checks CA certificates,
  proxies, USB debugging, device owner, patch level.
- Cell towers: learns the usual towers for your places and warns on things
  like 2G downgrades or a tower in the wrong area.
- Wi-Fi: evil-twin hotspots and a canary that only a Karma/Pineapple box
  answers.
- GPS spoofing and jamming checks; ultrasonic beacon listener; hidden-camera
  sweep (lens glint, IR, cameras on the Wi-Fi).
- E2EE messages and calls (vodozemac/Olm in sealed envelopes) through relays
  anyone can run, plus an offline Bluetooth mesh.

Things you should know:

- Not independently audited. Detection is best-effort; a finding is a reason
  to look, not proof. The camera sweep hasn't been validated on real hardware
  yet.
- The editions we publish include Google's play-services-location (used only
  where Play services exists; otherwise Android's own location service). A
  build without it exists in the source for F-Droid but isn't on F-Droid yet.
- A large part of the code was written with an AI coding assistant; the git
  history says so.
- No analytics, ads or crash reporting.

Install: APKs on GitHub Releases (pre-release) or clairix.app/download with
SHA-256; Obtainium works with "Include prereleases" on.

Source and the full limits section: https://github.com/jakes1345/Clairix

I'd especially like to hear about false positives and anything in the threat
model that looks wrong.
```
