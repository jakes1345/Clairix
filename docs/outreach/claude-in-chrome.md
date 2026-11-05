# Running the outreach with Claude in Chrome

This is a checklist for the Claude in Chrome browser extension. You run it on
your own computer, in your own browser, signed in to your own accounts. Claude
in Chrome opens pages, finds forms and fills them in. **You** read every field
and press the final Submit, Post or Send button yourself. Nothing in this file
has been sent. Written 1 November 2026 against 3.0.0 Beta 8.

## Before you start

- **Sign in yourself.** Log in to Codeberg, GitLab, Hacker News, Mastodon,
  Lemmy, Reddit and your email before you ask Claude in Chrome to do anything.
  Never type a password, recovery code or 2FA code into the Claude chat. Solve
  any captcha yourself.
- **Claude in Chrome cannot read this repository.** It sees only your browser.
  Each prompt below ends with "Here is the text:". Open the named file in
  `docs/outreach/`, copy the part the section names, and paste it under the
  prompt. (If these files have been pushed to GitHub, you can give it the file's
  URL on github.com instead.)
- **Fill in the placeholders first.** Replace `<your name>`, `<your email>` and
  `<journalist's name>` in the text before you paste it. Use your own address
  or support@clairix.app.
- **Read [README.md](README.md) first**, especially "Facts every draft relies
  on" and "Before anything goes out". Recheck the facts on the day you send:
  the release is still a pre-release, it is still not on Google Play, F-Droid,
  IzzyOnDroid or Accrescent, and it is still not independently audited.
- **Every prompt tells Claude in Chrome to stop before the final button.** If
  it ever offers to submit, post or send for you, say no and press it yourself
  after reading.
- **Update the "Every target" table** in [README.md](README.md) after each
  send: the date, and any reply.

## Do not

- Do not use alternate accounts, sock puppets, or anyone else's account.
- Do not buy votes, accounts, followers, reviews or upvotes, and do not ask
  friends to upvote or comment.
- Do not send mass DMs or cold messages to individual users. Email only the
  addresses listed here, one message each.
- Post once per community. Do not repost the same thing within a couple of
  weeks, and do not delete and repost to get another try.
- Always say you are the developer.
- Do not let Claude in Chrome write or post comments, replies or forum posts on
  its own. Answer people yourself, in your own words. (infosec.exchange bans
  LLM-operated accounts outright.)
- Do not hide or downplay that much of the code was written with an AI
  assistant. Do not claim an audit, a store listing, a user count, a partner or
  an endorsement that does not exist.

---

## 1. IzzyOnDroid inclusion request

- **Open:** https://codeberg.org/IzzyOnDroid/repodata/issues (believed to be the
  current route; confirm on the "New app inclusions" page linked from
  https://izzyondroid.org/docs/; the policy is at
  https://izzyondroid.org/docs/general/AppInclusionPolicy/). Do not use
  gitlab.com/IzzyOnDroid/repo: it is archived and read-only, even though
  `docs/DISTRIBUTION.md` still names it.
- **Account:** Codeberg.
- **Decide first.** Read "Read this first: the AI policy" in
  `izzyondroid-request.md`. The policy says vibe-coded apps will be rejected
  and that code should be free of LLM output. Clairix's history is largely
  AI-assisted, so a rejection is likely. Send only if you accept that and keep
  the disclosure in full. Not sending is a fine choice.
- **Prompt:**

  ```text
  Open https://codeberg.org/IzzyOnDroid/repodata/issues. First tell me whether
  the repository offers an issue template for new app inclusion requests, and
  list every field that template asks for. Then open a new issue with that
  template (or a blank issue if there is none). Put the title and body I paste
  below into the form, fitting the body into the template's fields without
  changing its meaning and without removing the AI disclosure. Tell me about
  any field the template asks for that my text does not answer. Do not press
  "Create issue" or any submit button: stop when the form is filled so I can
  review it. Here is the text:
  ```

  Paste: the title and body under "Text to paste" in `izzyondroid-request.md`.
- **Check before you press Create issue:**
  - The AI disclosure is there, unchanged.
  - The release `v3.0.0-beta.9` exists on GitHub and carries
    `clairix-v3.0.0-beta.9.apk`; the tag matches `versionName` `3.0.0-beta.9`
    and `versionCode` 29.
  - The signing certificate SHA-256 matches `docs/KEYS.md`.
  - The size (68.8 MB), the `play-services-location` dependency and the
    pre-release question are all stated.
  - No template field is left empty or answered with a guess.

## 2. Accrescent

- **Open:** https://accrescent.app/docs/guide/getting-started/new-app/
- **Account:** GitHub, and the account must be on Accrescent's allowlist.
- **State today:** closed to new developers. The docs say they are not
  accepting new allowlist requests. There is nothing to submit; this section
  only checks whether that has changed.
- **Prompt:**

  ```text
  Open https://accrescent.app/docs/guide/getting-started/new-app/ and tell me,
  quoting the page, whether Accrescent is currently accepting new developers or
  allowlist requests. Then open
  https://accrescent.app/docs/guide/appendix/requirements/ and list the
  requirements. Do not sign in, register, request access, or submit anything.
  ```

- **Check:** if it is still closed, stop here and leave the README table at
  "not sent (closed)". If it has reopened, read the requirements yourself
  before doing anything: an APK set (`.apks`), a 512x512 icon, a DNS TXT record
  `_accverify` proving the domain behind `app.clairix`, a v2+ signature, no
  self-updater. Questions go to the Matrix room #accrescent:matrix.org.

## 3. F-Droid (only what is possible today)

- **State today:** the published editions declare
  `com.google.android.gms:play-services-location`, which F-Droid does not
  accept. The `foss` flavour (`android/app/build.gradle.kts`) is built without
  Google Play services or Firebase, but has not been built on F-Droid's
  servers, and the open questions in `docs/DISTRIBUTION.md` ("F-Droid") are
  the maintainer's to settle first. Do not open a merge request to fdroiddata;
  the maintainer submits `docs/fdroid/app.clairix.yml` when ready.
- **Open (read only):** https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- **Account:** none for reading. GitLab later, when a qualifying build exists.
- **Prompt:**

  ```text
  Open https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/ and
  summarise, quoting the page, its rules on proprietary dependencies (Google
  Play services, Firebase, GMS) and on release tags. Do not sign in to GitLab,
  open any issue or merge request, or submit anything.
  ```

- **Check:** nothing to send. Point F-Droid-style users to IzzyOnDroid (if
  listed) or Obtainium. Revisit when a build without `play-services-location`
  exists; the routes then are https://gitlab.com/fdroid/rfp/issues and a merge
  request to https://gitlab.com/fdroid/fdroiddata adding
  `metadata/app.clairix.yml`.

## 4. Hacker News (Show HN)

- **Open:** https://news.ycombinator.com/submit (rules:
  https://news.ycombinator.com/showhn.html)
- **Account:** your own Hacker News account.
- **When:** only on a day you can stay and answer questions for several hours.
- **Prompt:**

  ```text
  Open https://news.ycombinator.com/showhn.html and list its rules for Show HN
  posts. Then open https://news.ycombinator.com/submit. Put the title I paste
  below in the "title" field and https://github.com/jakes1345/Clairix in the
  "url" field. Leave the "text" field empty. Do not press "submit": stop when
  the form is filled so I can review it. Here is the title:
  ```

  Paste: the line under "Title" in `show-hn.md`.
- **Check before you press submit:**
  - The title starts with `Show HN:` and is the one in `show-hn.md`.
  - The URL is the GitHub repository, not a landing page.
  - After submitting, **post the first comment yourself**, by hand, from
    "First comment" in `show-hn.md`. It discloses the AI-written code up front.
    Do not have Claude in Chrome post it or any later replies.
  - Do not ask anyone to upvote or comment.

## 5. Mastodon (infosec.exchange or your own instance)

- **Open:** your instance's home page, for example https://infosec.exchange/
  (rules: https://infosec.exchange/about and
  https://wiki.infosec.exchange/rules/start).
- **Account:** your own human account, one that also takes part in other
  discussion. infosec.exchange registration needs approval and a reason, and
  the minimum age is 18. Do not create an account just to promote Clairix.
- **infosec.exchange bans LLM- and AI-operated accounts.** So Claude in Chrome
  only checks the rules here. You paste the post and press Publish yourself.
- **Prompt:**

  ```text
  Open https://infosec.exchange/about and list the server rules, quoting any
  rule about spam, self-promotion, advertising, automated accounts, AI or LLM
  use, and content warnings. Do not open the compose box, type anything, or
  post anything.
  ```

- **Then, by hand:** open the compose box, paste the post under "Mastodon" in
  `mastodon-lemmy.md`, and add a content warning (for example "stalking,
  surveillance") if your instance expects one.
- **Check before you press Publish:** under 500 characters; it says beta, not
  independently audited, AI-assisted code, and not on Play or F-Droid; one post
  only; you will answer replies yourself.

## 6. Lemmy (lemmy.ml/c/privacy)

- **Open:** https://lemmy.ml/c/privacy
- **Account:** your own account on any federated Lemmy instance.
- **Prompt:**

  ```text
  Open https://lemmy.ml/c/privacy and tell me the community rules from its
  sidebar, and the site rules of lemmy.ml, quoting anything about
  self-promotion, ads, spam or reposting. Then open the "Create post" form for
  that community. Put the title I paste below in the title field, leave the URL
  field empty, and put the body I paste below in the body field. Do not press
  "Create" or any submit button: stop when the form is filled so I can review
  it. Here is the text:
  ```

  Paste: the title and body under "Lemmy" in `mastodon-lemmy.md`.
- **Check before you press Create:**
  - The post opens with the disclosure that you are the developer.
  - The rules still allow it (lemmy.ml site rule: no ads or spamming).
  - You are posting in one community only. Wait at least a couple of weeks
    before posting in lemmy.ml/c/degoogle, lemmy.ml/c/privatelife or
    lemmy.ca/c/privacy, and only if their own rules allow it.
  - Do not post in the Privacy Guides Lemmy community; it reportedly bans
    self-promotion of projects not listed on privacyguides.org.

## 7. Privacy Guides forum

- **Open:** https://discuss.privacyguides.net/t/about-the-project-showcase-category/114
- **Account:** your own Discourse account on discuss.privacyguides.net.
- **Rules:** self-promotion is allowed only in "Project Showcase", which is for
  verified accounts whose work Privacy Guides recommends. Clairix is not
  recommended there. So **do not post a thread**. The only step today is to ask
  @team for permission.
- **Prompt:**

  ```text
  Open https://discuss.privacyguides.net/t/about-the-project-showcase-category/114
  and quote the rules about who may post, verification, and self-promotion.
  Then open a new private message to the group @team
  (https://discuss.privacyguides.net/g/team). Put the subject and message I
  paste below into it. Do not press "Send": stop when it is filled so I can
  review it. Do not create any topic or reply in any category. Here is the
  text:
  ```

  Paste: your own short message. Adapt the "Message to the moderators" in
  `reddit.md` (say you are the developer, Clairix is not recommended on
  privacyguides.org, and ask whether a showcase post is allowed or whether it
  should wait). Do not link the project anywhere else on the forum.
- **Check before you press Send:** it is a private message to @team, not a
  public topic; it asks permission and does not pitch; if they say no, that is
  the answer.

## 8. Reddit (participation first)

- **Open:** https://www.reddit.com/r/privacy/about/rules/ and
  https://www.reddit.com/r/degoogle/about/rules/
- **Account:** your own ordinary Reddit account. Many subreddits set
  account-age and karma limits.
- **Order:** follow "The plan" in `reddit.md`. Take part in both subreddits for
  a few weeks first, in your own words, without linking Clairix. Claude in
  Chrome must not write or post those comments for you. Then read the rules,
  then ask the moderators, then post only where and how they say.
- **Prompt (rules, both subreddits):**

  ```text
  Open https://www.reddit.com/r/privacy/about/rules/ and then
  https://www.reddit.com/r/degoogle/about/rules/. For each, list every rule
  and quote anything about self-promotion, developer posts, showcase threads,
  account age or karma. Also check each subreddit's sidebar for a weekly
  showcase or self-promotion thread. Do not post, comment, vote or message
  anyone.
  ```

- **Prompt (modmail, one subreddit at a time):**

  ```text
  Open https://www.reddit.com/r/privacy and use the "Message the mods" link
  in its sidebar to open a message to the r/privacy moderators. Put the subject and message I paste below into it.
  Do not press "Send": stop when it is filled so I can review it. Here is the
  text:
  ```

  Paste: "Message to the moderators" from `reddit.md`. For r/degoogle, change
  `r/privacy` to `r/degoogle` in the prompt.
- **Prompt (only after the moderators say yes):** the same form-filling prompt
  pointed at the place they named (a post, or the weekly showcase thread), with
  the text from "Post, for when the moderators allow it" in `reddit.md`, ending
  "Do not press Post: stop when the form is filled so I can review it."
- **Check before you press Send or Post:** you are the developer and it says
  so; one post per subreddit; for r/degoogle, the post says first that every
  edition includes `play-services-location` and what happens without Play
  services; nobody is asked to upvote.

## 9. Organisation emails and forms

All texts are in `orgs.md`, one section per organisation. Each asks for
feedback or advice, never endorsement, a listing or money. Before sending,
check that https://clairix.app/advocates is live: the emails link it.
`website/src/pages/advocates.astro` exists in the working tree but, as
`README.md` says, was not committed or deployed on 1 November 2026, so open the
URL and confirm it works before sending; if it does not load, publish it first
or delete the advocates line from each email. Send one at a time.

**Prompt for an email (fill the two placeholders):**

```text
Open my email (I am already signed in) and start a new message to <address>.
Put the subject and body I paste below into it, exactly as given. Do not add
attachments or anyone in CC or BCC. Do not press "Send": stop when the draft
is ready so I can review it. Here is the text:
```

**Prompt for a web form (fill the placeholder):**

```text
Open <form URL>. List every field on the form and say which are required.
Fill the fields from the text I paste below: my name, my email, and the
message. For any field my text does not answer (for example a mailing address
or an organisation name), leave it empty and tell me. Do not press "Submit" or
"Send": stop when the form is filled so I can review it. Here is the text:
```

| Order | Organisation | Route | Prompt | Text in `orgs.md` |
|---|---|---|---|---|
| 1 | NNEDV Safety Net Project | Survey https://www.surveymonkey.com/r/techconsult (from https://www.techsafety.org/technology-consultations). Questions only: safetynet@nnedv.org | Web form | "NNEDV Safety Net Project" |
| 2 | Coalition Against Stalkerware | Partner form on https://stopstalkerware.org/about/ | Web form | "Coalition Against Stalkerware" |
| 3 | Operation Safe Escape | Form https://safeescape.org/contact-us/ | Web form | "Operation Safe Escape" |
| 4 | Freedom of the Press Foundation | info@freedom.press (or the form at https://freedom.press/contact/) | Email | "Freedom of the Press Foundation (Digital Security)" |
| 5 | Electronic Frontier Foundation | info@eff.org | Email | "Electronic Frontier Foundation" |
| 6 | Open Technology Fund | Portal https://apply.opentech.fund/ ; questions to hello@opentech.fund | See below | "Open Technology Fund (Red Team Lab / Security Lab)" and `docs/audit/OTF-SECURITY-LAB-APPLICATION.md` |
| - | Access Now Digital Security Helpline | help@accessnow.org | **Do not send** | "Access Now Digital Security Helpline" |

**Check before each Send or Submit:**

- It asks for feedback, not endorsement. Nothing implies the organisation has
  reviewed, approved or partnered with Clairix.
- It states the limits: beta, best-effort detection, not independently audited,
  APK only, not on Google Play.
- Your name and email are filled in, and no placeholder is left.
- NNEDV: Safety Net does not endorse products or give money; expect a reply in
  1 to 2 weeks.
- Coalition Against Stalkerware: the "contribution you would like to make"
  field names something concrete; do not imply that joining means endorsement.
- Operation Safe Escape: the message says plainly you are a developer offering
  a free GPLv3 tool and asking for feedback, not seeking clients. The form is
  mainly for survivors; keep it short.
- Open Technology Fund: do not let Claude in Chrome submit an application.
  Use this prompt to read the portal only:

  ```text
  Open https://apply.opentech.fund/ and tell me which funds or labs are open
  for applications, their deadlines, and the fields each application form asks
  for. Do not sign in, create an account, or start or submit any application.
  ```

  Then sign in and fill the application yourself, leading with Comms (Tor,
  mesh, end-to-end encryption).
- Access Now: the helpline gives direct support to at-risk civil society under
  its mandate (https://www.accessnow.org/helpline-mandate/). It is not a channel
  for promoting apps. Do not send the app to it.

## 10. Press

Texts are in `press.md`: the "Pitch" and the "Fact sheet". Tip lines want a
story, not a launch note. Send to one journalist per outlet, personalise the
first line to something they wrote, and follow up at most once. First make the
press page (which says there is no named spokesperson) and the signed pitch
agree, as `press.md` says.

- **404 Media.** Joseph Cox, joseph@404media.co (Signal `joseph.404`). Their
  page asks you to use a non-work phone. Use the email prompt from section 9.
  Signal messages you send yourself, from your phone.
- **The Verge.** tips@theverge.com (email prompt), or
  https://www.theverge.com/contact choosing "I want to pitch a story" (form
  prompt).
- **Ars Technica.** Form at https://arstechnica.com/contact-us/, department
  "Tips, Suggestions, and Press Releases". Use the form prompt and add: "Choose
  the department 'Tips, Suggestions, and Press Releases'."
- **Wired.** Not verified. Use this prompt, then the email or form prompt with
  whatever it finds on wired.com itself:

  ```text
  Open https://www.wired.com/ and find the official "Tips" or "Contact" link
  in the site footer. Tell me the exact route it gives for news tips (email
  address, form or SecureDrop) and the page URL where you found it. Use only
  what is on wired.com; do not use an address from any other site. Do not
  fill in or send anything.
  ```

- **Check before each Send:** the pitch is a story with evidence, not an ad; it
  says beta, not independently audited, AI-assisted code, no detection rates
  claimed, not on Google Play; the journalist's name and the personal first
  line are filled in.

## 11. Creators

Text is in `creators.md`, under "Pitch".

- **Techlore.** contact@techlore.tech (from https://techlore.tech/contact/).
  First read their protocols yourself, or ask Claude in Chrome to summarise
  them:

  ```text
  Open https://techlore.tech/techlore-protocols/ and summarise their rules on
  sponsorships, review requests and editorial independence. Do not fill in or
  send anything.
  ```

  Then use the email prompt from section 9 with contact@techlore.tech.
- **The Hated One.** Not verified: thehatedone.com is parked. The only possible
  official route is the "View email address" button on the About tab of their
  YouTube channel, which needs a captcha. Use this prompt and solve the
  captcha yourself:

  ```text
  Open the official YouTube channel of "The Hated One" and go to its About
  section. Find the "View email address" button and stop there so I can solve
  the captcha myself. Do not use any email address from a site other than
  YouTube. Do not send anything.
  ```

  If an address appears, use the email prompt from section 9. If none does,
  do not send.
- **Check before each Send:** you ask them to take a look, with no request for
  coverage on any terms and no payment offered; the AI-assisted code and the
  beta status are stated; one email per creator, one follow-up at most.

## Optional: Obtainium config

Obtainium already works with no listing: /download links
`obtainium://add/https://github.com/jakes1345/Clairix`, and users turn on
"Include prereleases" and set the filter `clairix-v[^/]*\.apk$`. If you want a
preset in the crowdsourced directory, first check the repository for an
existing Clairix entry and its contribution guidelines:

```text
Open https://github.com/ImranR98/apps.obtainium.imranr.dev and tell me how
to request or add an app config (issue template or pull request), and whether
a config for github.com/jakes1345/Clairix already exists. Do not open an
issue or pull request.
```
