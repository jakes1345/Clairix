# Organisations

One email per organisation, each under 200 words. Every one asks for feedback
or advice, not an endorsement, a listing or money. None of these groups has
reviewed or approved Clairix; do not say or imply otherwise afterwards.

**Before sending:** the emails link `https://clairix.app/advocates`. The page
is written (`website/src/pages/advocates.astro`) but was not committed or
deployed on 1 November 2026. Check the URL loads before sending; if not,
publish it first or delete the line. Replace `<your name>` and `<your email>`.

---

## NNEDV Safety Net Project

Route: the Technology Consultation Request Survey,
https://www.surveymonkey.com/r/techconsult (linked from
https://www.techsafety.org/technology-consultations). Paste the body into the
survey's free-text answers. Questions only: safetynet@nnedv.org. Safety Net
does not endorse products or give money; expect a reply in 1 to 2 weeks.

```text
Subject: Request for survivor-safety feedback on a free anti-stalking app

Hello,

I'm <your name>, the developer of Clairix, a free, open-source (GPLv3) Android
app. I'm asking for feedback on survivor safety, not an endorsement.

What it may help with: checking a phone for stalkerware (apps scored on the
access stalkerware needs and matched against the Echap indicator list),
noticing a Bluetooth tracker that travels with someone, and a Get help page
of helplines. Before a survivor removes anything, it warns that removal can
alert whoever placed it and offers to save evidence first.

Honest limits: detection is best-effort; it cannot see past a compromised
operating system; an abuser who handles the unlocked phone can see the app is
installed; and it has not been independently audited. It is a beta,
distributed as an APK, not through Google Play.

I'd value your view on where it could put a survivor at risk. A short page for
advocates: https://clairix.app/advocates. I'm glad to join a call.

<your name>
<your email>
https://github.com/jakes1345/Clairix
```

---

## Coalition Against Stalkerware

Route: the partner enquiry form on https://stopstalkerware.org/about/. Fill
in organisation name ("Clairix, an independent open-source project"), your
name, email, mailing address (`<your mailing address>`), website
(https://clairix.app), the description, and the contribution below.

```text
Description of the organisation:
Clairix is a one-person, open-source (GPLv3) Android app that checks phones
for stalkerware and Bluetooth trackers. No ads, analytics or crash reporting.
It has not been independently audited.

The contribution I would like to make:
Clairix already uses the Echap stalkerware indicators (CC BY 4.0) to match
apps by package name and signing certificate. I can contribute back:
- any stalkerware samples or certificates people send me by email that
  the list misses, passed to the indicator maintainers (the app has no
  in-app reporting channel for this);
- the app's scoring rules, which are open, for review by your technical
  members, and changes when they find mistakes;
- survivor-safety fixes your members point out, made in the open.

I am not asking for an endorsement or to use the Coalition's name. I would
value technical and survivor-safety feedback, and I'm happy to talk on a call.
Advocates' page: https://clairix.app/advocates

<your name>, <your email>
```

Only promise the first bullet if you will really do it; otherwise drop it.

---

## Operation Safe Escape

Route: the contact form at https://safeescape.org/contact-us/ (name, email,
message). The form is mainly for survivors; say at once that you are not one
asking for help and not seeking clients.

```text
Hello. I'm not a survivor asking for help, and I'm not looking for clients.
I'm <your name>, a developer, and I've made Clairix, a free, open-source
(GPLv3) Android app for checking a phone for stalkerware and Bluetooth
trackers, with end-to-end encrypted messaging and an SOS.

I'd be grateful if someone on your team who works on technology safety could
tell me where it gets things wrong for the people you help.

Its limits, so nobody over-trusts it: detection is best-effort; it cannot see
past a compromised operating system; someone who handles the unlocked phone
can see it is installed; it is a beta, installed from an APK rather than
Google Play; and it has not been independently audited.

A page for advocates: https://clairix.app/advocates. Source:
https://github.com/jakes1345/Clairix. I'm happy to have a call at a time that
suits you.

<your name>
<your email>
```

---

## Freedom of the Press Foundation (Digital Security)

Route: info@freedom.press or https://freedom.press/contact/. Their digisec
team serves journalists and their sources and has no app-review process.

```text
Subject: Feedback request: open-source Android app for spotting trackers and stalkerware

Hello,

I'm <your name>, and I maintain Clairix, a free, GPLv3 Android app. I know you
don't review or list apps; I'm writing in case someone on the digital
security team would be willing to tell me whether it is useful, or harmful,
for journalists and sources.

What it does: flags Bluetooth trackers that travel with you, scores apps for
stalkerware behaviour, checks user-installed certificates and proxies, warns
on fake Wi-Fi and suspicious cell towers (2G downgrades), and offers
end-to-end encrypted messages and calls through relays anyone can run, with
optional Tor.

Limits: it has not been independently audited (vodozemac, its Olm library,
has been); detection is best-effort; it cannot see past a compromised OS;
and every published edition includes Google's location library.

Threat model and limits: https://github.com/jakes1345/Clairix#who-it-is-built-against
Advocates' page: https://clairix.app/advocates

I'd welcome a short call if anyone has time.

<your name>
<your email>
```

---

## Electronic Frontier Foundation

Route: info@eff.org. A cold general enquiry; there is no route for app review
and no promise of a reply.

```text
Subject: Open-source anti-stalking app: asking for feedback, not endorsement

Hello,

I'm <your name>, and I develop Clairix, a free, GPLv3 Android app that checks
for stalkerware, Bluetooth trackers, fake cell towers and fake Wi-Fi, and has
end-to-end encrypted messaging through relays anyone can run. It uses the
Echap stalkerware indicators to match apps by package and signing certificate.

I'm not asking for an endorsement. If anyone working on stalkerware or
surveillance self-defence has a moment, I'd like to know what it gets wrong,
especially in the threat model and in how it tells people about a finding.

To be clear about where it stands: it is a beta, distributed as an APK (not on
Google Play or F-Droid), it has not been independently audited, and detection
is best-effort. Much of the code was written with an AI coding assistant, which
the commit history shows.

Source and limits: https://github.com/jakes1345/Clairix
Advocates' page: https://clairix.app/advocates

Glad to talk on a call if that's easier.

<your name>
<your email>
```

---

## Open Technology Fund (Red Team Lab / Security Lab)

Route: the application itself goes through https://apply.opentech.fund/. A
draft is in `docs/audit/OTF-SECURITY-LAB-APPLICATION.md`; update it from Beta 7
to Beta 9 and fill in the items it lists before submitting. Use this email
only for a question first, to hello@opentech.fund. Lead with the Comms side:
OTF funds internet-freedom tools.

```text
Subject: Question before applying for a Security Lab audit

Hello,

I'm <your name>, maintainer of Clairix, a free, GPLv3 Android app for people
whose providers can be compelled: end-to-end encrypted messages and calls
(vodozemac/Olm in sealed envelopes, blind send tokens per RFC 9497) through
relays anyone can run, optional Tor through Arti, and an offline Bluetooth
mesh, alongside checks for fake cell towers, trackers and spyware.

It has never been independently audited, and I'd like it to be. Our audit
pack (threat model, architecture, prioritised scope, build and test guide) is
at https://github.com/jakes1345/Clairix/tree/main/docs/audit.

Before I apply: does a one-person project, applying as an individual, fit the
Security Lab, and is there anything the portal asks that I should prepare?

Thank you,
<your name>
<your email>
```

---

## Access Now Digital Security Helpline

**Do not send a pitch.** The helpline (help@accessnow.org) gives direct
support to at-risk civil society under its mandate
(https://www.accessnow.org/helpline-mandate/). It is not a channel for
promoting software, and an unsolicited app pitch takes time from people in
danger. Write to them only if you, or someone you are helping, has a real
security incident that fits the mandate, and then write about the incident,
not about Clairix.
