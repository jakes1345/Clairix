# Abuse on the Clairix relay

How a report of abuse reaches the operator of the Clairix project's relay,
what the operator can see when deciding, what they can do about it, and how
someone acted against can appeal. The tools are the relay's own
([comms-worker/README.md](../comms-worker/README.md), "Abuse reports" and
"Suspending a number"); anyone running a relay of their own can start from
this page and set their own policy. The response times below are the project
maintainer's commitments and apply to the project's relay only.

## What the relay can and cannot know

The relay carries end-to-end encrypted envelopes. It never sees what anyone
wrote, said or sent, and it is not told who sent an envelope: a send spends an
anonymous token and names no account. About a number it holds the public
keys, whether the number is listed, how many envelopes wait for it and, once
someone reports it, the reports. It keeps no request logs.

So the operator cannot read a conversation to check a report, cannot find the
other numbers the same person uses, and cannot see who anyone talks to. Every
decision rests on what people choose to show.

## Intake

There are two ways in.

1. **Report, in the app**, on a contact's page or on a message request. The
   phone sends the relay the number, the relay it is on when that is another
   one, a reason (harassment, threats, spam, impersonation or other) and the
   SHA-256 of the last envelope that number sent, when there is one. The
   report spends an anonymous token, so the operator does not learn who sent
   it and cannot write back. The relay keeps reports by the day, for 90 days
   at most or until the operator closes them.
2. **Email to support@clairix.app**, from anyone, on Clairix or not. Say
   which Clairix number, what happened and when, and attach screenshots of the
   conversation that show the number. We reply within five business days, and
   the same day for threats of violence.

A report about a number on another relay belongs to that relay's operator.
The relay list names who runs each listed relay; for any other relay, its
address names its host. With the reporter's agreement we pass on what they
sent us. This relay can also stop taking envelopes from a relay that does not
act, with its `FEDERATION` setting.

The operator reads the in-app reports at least once a week:

    ADMIN_SECRET=… node scripts/operator.mjs reports https://clairix-relay.bigjacob710.workers.dev

and the support mailbox every business day.

## Evidence

What the operator can look at:

- The in-app reports about the number: how many there are, their reasons,
  the first and last day, and the envelope fingerprints.
- What an email reporter sends: screenshots, their account of what happened,
  and their answers to questions.

What none of it proves, and how it is weighed:

- A report is someone's word. One person can send many in-app reports, and an
  envelope fingerprint cannot be checked against anything, because the relay
  deletes an envelope once it is delivered. Many reports may be one person.
- Screenshots can be made up too, but they show what was said, and the person
  who sent them can be asked about it.
- Nothing shows who holds a number. A suspension acts on the number.

We suspend a number when what we were shown makes it more likely than not
that it was used against someone in breach of section 5 of the
[terms](https://clairix.app/terms): threats, harassment, stalking or helping
someone stalk, impersonation, spam or malware. Threats of violence, and
anything that puts a child at risk, are acted on the day we see them; the rest
within five business days of the report that settles it. In-app reports on
their own lead to a suspension only when they come on several days, name
several different envelopes and give threats or harassment as the reason;
otherwise they tell us which numbers to watch.

## Action

The operator suspends the number:

    ADMIN_SECRET=… node scripts/operator.mjs suspend https://clairix-relay.bigjacob710.workers.dev "482 913 605"

From that moment:

- The number cannot sign in. Its phone collects nothing and is issued no new
  tokens, and every request it makes is answered with a sentence saying the
  number is suspended and where to appeal.
- Its live connection is closed and its push endpoint deleted, so nothing
  wakes the phone.
- To everyone else it reads as a number that does not exist: messages to it
  are accepted and dropped, as for a number nobody holds; nobody can look it
  up or add it; a payment to it is refused. Nobody learns from the relay that
  it was suspended.
- What was already waiting for it stays on the relay, and reaches it if the
  suspension is lifted within 30 days; after that it expires as it would
  anyway.

What a suspension does not do:

- Sending needs no sign-in, only a token, and the relay cannot tell whose
  tokens are whose. A suspended phone can still send with the tokens it
  already holds, a hundred or so at most, over the four weeks they last.
  People it writes to can block it on their phones.
- It stops a number, not a person. Someone suspended can come back with a new
  number if someone on the relay invites them. A new number we are shown to be
  the same person's is suspended on the same evidence.
- It takes nothing back: messages already delivered stay on the phones that
  received them.
- It does nothing on other relays.

After acting, the operator closes the reports about the number:

    ADMIN_SECRET=… node scripts/operator.mjs close https://clairix-relay.bigjacob710.workers.dev "482 913 605"

tells an email reporter that the number was suspended (people who reported in
the app cannot be told), and keeps a note in the support mailbox: the date,
the number, what was seen and the decision. The relay itself keeps only that
the number is suspended and the day it began.

Every suspension is looked at again after 90 days. Unless an appeal is open
or new reports have come in, the account is then deleted with
`scripts/delete-account.mjs` (comms-worker/README.md, "Deleting an account on
request"), which also deletes what was still queued for it.

## Appeal

A suspended phone is told, every time it tries to sign in, that its number is
suspended and to write to support@clairix.app (the relay's `OPERATOR_CONTACT`
setting). To appeal, write there with the number and why the suspension is
wrong. Nothing on the relay can confirm who is writing, so an appeal is read
on what it says.

We reply within five business days and decide within ten. The reply says what
kind of report led to the suspension, never who made it. If the appeal
succeeds the suspension is lifted at once:

    ADMIN_SECRET=… node scripts/operator.mjs unsuspend https://clairix-relay.bigjacob710.workers.dev "482 913 605"

and the phone signs in again on its next try, with whatever was queued for it
in the last 30 days. While the project has one maintainer, the same person
who suspended a number decides its appeal, and the reply says so.

## Setting it up on the project's relay

Once, by the maintainer: make the operator identity and give the relay its
settings.

    ENROLL_SECRET=… node scripts/operator.mjs register https://clairix-relay.bigjacob710.workers.dev

then set `OPERATOR_KEYS` to the key it prints, `OPERATOR_CONTACT` to
`support@clairix.app` and, if it is not set yet, `ADMIN_SECRET` on the live
Worker. Until `OPERATOR_KEYS` and `ADMIN_SECRET` are both set nobody can be
suspended; until `OPERATOR_CONTACT` is, a suspended phone is told to appeal to
whoever runs the relay.
