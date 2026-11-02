# Outreach kit

Drafts for telling stores, organisations, communities and the press about
Clairix. Written 1 November 2026 against 3.0.0 Beta 8. Nothing here has been
sent. Every draft is in my voice as the maintainer of one small independent
project; fill in `<your name>` and `<your email>` (or use
support@clairix.app, the address the website already publishes) before sending.

## What is in the kit

| File | What it is |
|---|---|
| [izzyondroid-request.md](izzyondroid-request.md) | The IzzyOnDroid inclusion request, ready to paste, plus the AI-policy problem to read first |
| [orgs.md](orgs.md) | One email per organisation (survivor support, digital rights, funders) |
| [show-hn.md](show-hn.md) | Show HN title and first comment |
| [mastodon-lemmy.md](mastodon-lemmy.md) | A Mastodon post and a Lemmy post |
| [reddit.md](reddit.md) | How to approach r/privacy and r/degoogle, and a post for when it is allowed |
| [press.md](press.md) | A pitch for journalists and a fact sheet |
| [creators.md](creators.md) | A short pitch for privacy YouTubers |

## Facts every draft relies on (check them again before sending)

- **Where to get it:** GitHub Releases (every release so far is a beta marked
  as a pre-release) and https://clairix.app/download, which links the Full APK
  with its SHA-256. Obtainium works with "Include prereleases" on and the APK
  filter `clairix-v[^/]*\.apk$`.
- **Not on Google Play** (`PLAY_LIVE` is `false`), **not on F-Droid** (every
  edition includes `play-services-location`), **not on IzzyOnDroid** (not
  requested yet), **not on Accrescent**.
- **Not independently audited.** vodozemac, the Olm library it uses, has been
  audited; Clairix has not. Our own findings are in `docs/SECURITY-FINDINGS.md`
  and the open ones in `docs/OPEN-ISSUES.md`.
- **No hardware validation recorded** for the hidden-camera sweep
  (`docs/CAMERA-SWEEP-VALIDATION.md`) or the SDR/OBD features
  (`docs/audit/HARDWARE-RUNS.md`). Do not claim detection rates.
- **Rebuild check:** each release is rebuilt from its tag on a separate runner
  and compared entry by entry (`tools/reproducible`,
  `verify-release.yml`). That is our own CI check, not third-party
  verification.
- **Licence:** GPL-3.0, with a section 7 permission for Google Play services.
- **No analytics**, so there is no user count. Say so if asked.
- **AI-written code.** On 1 November 2026 the development branch
  (`treason/wizardly-ritchie-euu6y1`) has 330 commits; 220 of them involve
  Claude: 208 are authored as "Claude" and 206 carry a `Co-Authored-By: Claude`
  line (most carry both). Recount on the branch you link before sending
  (`git log --format='%an%n%B%x00'`). Say this plainly
  wherever it matters (IzzyOnDroid, Hacker News, press). Hiding it is not an
  option, and some places will reject or criticise the project for it.
- **APK size:** 68.8 MB for `clairix-v3.0.0-beta.8.apk`.

## Before anything goes out: fix these in the repo

1. **The `/advocates` page is not live yet.** `orgs.md` links to
   `https://clairix.app/advocates`. On 1 November 2026 `website/src/pages/advocates.astro`
   and `website/src/content/docs/advocates.md` exist in the working tree but are
   not committed or deployed. Open the URL in a browser before sending; if it
   does not load, publish it first or delete that line from each email.
2. **`docs/DISTRIBUTION.md` names a dead IzzyOnDroid route.** It says requests
   go to `gitlab.com/IzzyOnDroid/repo`, which is archived and read-only. The
   current place appears to be https://codeberg.org/IzzyOnDroid/repodata/issues;
   confirm it on the "New app inclusions" page linked from
   https://izzyondroid.org/docs/, then correct the file.
3. **The press page** says Clairix "is published without a named spokesperson"
   and answers are attributed to "the Clairix team". The drafts here are in the
   first person with a name placeholder. Decide which you want and make the
   press page and these drafts agree.

## Order to do things in

1. **Stores first**, so that a reader who arrives from anywhere else can
   install from a channel they trust.
   - Obtainium already works. Optionally request a config in the crowdsourced
     directory.
   - IzzyOnDroid: read its AI policy first (see `izzyondroid-request.md`). A
     rejection is likely; send only if you are willing to disclose fully.
   - F-Droid and Accrescent: nothing to send today. Revisit when a build without
     Google Play services exists (F-Droid) or Accrescent reopens sign-up.
2. **Organisations next**, asking for feedback, never endorsement. Start with
   the survivor-safety groups (NNEDV Safety Net, Coalition Against Stalkerware),
   then digital rights, then OTF.
3. **Communities and press last**, once the stores step is settled and someone
   who is around can answer questions for a few days. One honest, disclosed
   post per community; no reposts within a couple of weeks; no vote requests;
   no second accounts.

## Every target

Status is "not sent" for all of them. "Find the current contact on ..." means
the route could not be verified; look it up in a browser and do not use an
address from a third-party site.

| # | Target | Kind | Route | Requirements and notes | Status |
|---|---|---|---|---|---|
| 1 | Obtainium | store | Nothing to submit; /download already has `obtainium://add/https://github.com/jakes1345/Clairix`. Optional config request: https://github.com/ImranR98/apps.obtainium.imranr.dev/issues or a PR to that repo | GitHub account for the optional config. Users need "Include prereleases" and the regex `clairix-v[^/]*\.apk$` | not sent |
| 2 | IzzyOnDroid | store | Issue at https://codeberg.org/IzzyOnDroid/repodata/issues (contact page: https://izzyondroid.org/contact) | Codeberg account. Policy: https://izzyondroid.org/docs/general/AppInclusionPolicy/. **AI policy rejects vibe-coded apps and code written by LLMs; our history is mostly AI-assisted.** Also: ~30 MB rule of thumb vs our 68.8 MB; `play-services-location`; pre-releases only | not sent |
| 3 | F-Droid (main repository) | store | RFP at https://gitlab.com/fdroid/rfp/issues; real submission is a merge request to https://gitlab.com/fdroid/fdroiddata adding `metadata/app.clairix.yml` | GitLab account; only FOSS dependencies. **Not possible today**: every edition depends on `play-services-location` | not sent (blocked) |
| 4 | Accrescent | store | https://console.accrescent.app (GitHub login, allowlist only); questions in #accrescent:matrix.org | **Closed to new developers.** If it reopens: .apks set, 512x512 icon, DNS TXT `_accverify` proof, v2+ signature, rules at https://accrescent.app/docs/guide/appendix/requirements/ | not sent (closed) |
| 5 | NNEDV Safety Net Project | org | Technology Consultation Request Survey https://www.surveymonkey.com/r/techconsult; questions to safetynet@nnedv.org | Does not endorse products or fund. Ask for feedback on survivor safety. Reply in 1 to 2 weeks; may lead to a call or a paid consultation | not sent |
| 6 | Coalition Against Stalkerware | org | Partner enquiry form on https://stopstalkerware.org/about/ | No written criteria; partners are support groups, digital-rights groups, security companies, researchers. Offer something concrete; joining is not endorsement | not sent |
| 7 | Operation Safe Escape | org | Contact form https://safeescape.org/contact-us/ | Form is mainly for survivors. Say plainly this is a developer asking for feedback, not seeking clients | not sent |
| 8 | Freedom of the Press Foundation | org | info@freedom.press or https://freedom.press/contact/ | Serves journalists and sources; no app-review process. Cold enquiry | not sent |
| 9 | Electronic Frontier Foundation | org | info@eff.org (press: press@eff.org) | No app-review route. Cold enquiry; no promise of a reply | not sent |
| 10 | Open Technology Fund | org | Portal https://apply.opentech.fund/ ; questions to hello@opentech.fund | Internet-freedom focus: lead with Comms (Tor, mesh, E2EE). Draft already in `docs/audit/OTF-SECURITY-LAB-APPLICATION.md` | not sent |
| 11 | Access Now Digital Security Helpline | org | help@accessnow.org | Direct support for at-risk civil society under its mandate (https://www.accessnow.org/helpline-mandate/). **Not a channel for promoting apps. Do not pitch.** | not sent (do not pitch) |
| 12 | Hacker News (Show HN) | community | https://news.ycombinator.com/submit, title starting "Show HN:" | Own work, easy to try, non-trivial, be around to answer, no upvote requests. Expect AI questions; answer honestly | not sent |
| 13 | Lemmy: lemmy.ml/c/privacy | community | Post in https://lemmy.ml/c/privacy (related: lemmy.ml/c/degoogle, lemmy.ml/c/privatelife, lemmy.ca/c/privacy) | No ads or spam; one honest disclosed post; wait a couple of weeks between reposts | not sent |
| 14 | Mastodon: infosec.exchange | community | Post from a human account; rules https://infosec.exchange/about | Registration needs approval; no promo-only accounts; no LLM-operated accounts; use CWs where needed | not sent |
| 15 | Privacy Guides forum | community | Message @team first: https://discuss.privacyguides.net/g/team ; only place is "Project Showcase" | Self-promotion banned elsewhere; showcase is for verified projects recommended by Privacy Guides. Ask before posting anything | not sent |
| 16 | r/privacy | community | Find the current rules on https://www.reddit.com/r/privacy/about/rules/ (not verified) | Unknown; check in a browser, or ask the moderators | not sent |
| 17 | r/degoogle | community | Find the current rules on https://www.reddit.com/r/degoogle/about/rules/ (not verified; a weekly showcase thread is likely) | Unknown. The Google Play services dependency will be noticed | not sent |
| 18 | 404 Media | press | Joseph Cox: joseph@404media.co, Signal joseph.404 (https://www.404media.co/contact-us-tips/) | Tip line, not PR. Needs a story | not sent |
| 19 | The Verge | press | tips@theverge.com or https://www.theverge.com/contact | Wants a story with firsthand evidence, not an ad | not sent |
| 20 | Ars Technica | press | Form at https://arstechnica.com/contact-us/, department "Tips, Suggestions, and Press Releases" | Form only; keep it factual | not sent |
| 21 | Wired | press | Find the current contact on https://www.wired.com/ (not verified; look for "Tips" or "Contact" in the footer) | Unknown; use no guessed address | not sent |
| 22 | Techlore | creator | contact@techlore.tech (https://techlore.tech/contact/) | Read https://techlore.tech/techlore-protocols/ first; editorially independent | not sent |
| 23 | The Hated One | creator | Find the current contact on https://thehatedone.com/contact (not verified; the site is parked; the YouTube About tab may show an email behind a captcha) | Unknown; no third-party addresses | not sent |

Keep this table up to date: change "not sent" to the date sent, and note any
reply.
