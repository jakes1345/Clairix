---
id: comms
order: 1
title: "Talk privately"
kicker: "Private comms"
icon: comms
variant: cipher
visualLabel: "Sealed envelope"
visualCaption: "The relay cannot open it"
---

<div class="f-does">

### What it does {#comms-does}

End-to-end encrypted messages, voice notes, photos, videos and voice calls between Clairix apps, addressed by a Clairix number instead of a phone number. Groups for a family, a team or a community. SOS alerts to everyone in your group. Relays anyone can run, a way to move to another one when yours is blocked, Tor built in, and an offline mesh that carries texts and SOS phone to phone over Bluetooth when there is no network.

</div>

### How it works {#comms-how}

<div class="methods">

**Clairix numbers.** At registration our relay gives your phone a random nine-digit Clairix number. It is not a phone number: it cannot call or text a phone, and a phone cannot reach it. What it buys you is a handle you can give someone without giving them your real number or your identity. You can choose whether your number can be looked up. Unlisted numbers are reached only by scanning your QR code in person.

**Pairing and invites.** Two people pair by scanning each other's QR code in person, or by entering a listed number and then comparing safety numbers. From then on each phone has the other's identity key pinned. Someone not yet on Clairix can be sent an invite: a link to clairix.app that Android opens in Clairix alone, because the site names the app and its signing key, so no other app can take it. Before you register from an invite, the app names the relay it would put you on and says whether that relay is on the signed relay list.

**Relays.** Anyone can run a Clairix relay, with one script on a server of their own, behind HTTPS and an onion address. Setup offers a signed list of relays and checks a listed relay still has the key the list gives it. A contact on another relay is added by scanning their QR code, which names their relay. Everything for them goes to your relay, which forwards it to theirs signed with its own key. Their relay sees your relay's address, never your phone's, and nothing in the request names you as the sender. Your relay sees which relay and which number you write to.

**Moving to another relay.** If your relay is blocked where you are, shut down, or no longer one you trust, Comms settings → Move to another relay takes you elsewhere. You keep your keys, contacts, conversations and verified contacts and get a new number there. Every contact is told over the encrypted session you already share, and their phones switch over by themselves. What they send to your old number before then is collected from the old relay whenever it can be reached, for 30 days.

**Tor.** One switch sends everything Clairix says to relays through Tor, built into the app: no relay learns your network address, and your network does not learn which relay you use. A relay with an onion address is reached there, so the connection never leaves Tor.

**Encryption.** Every message is encrypted on your phone for one contact with the Olm double ratchet, using vodozemac, the audited Rust implementation used by Matrix. Each message uses a fresh key, so a key stolen later cannot decrypt earlier messages, and a session recovers on its own after a compromise. The encrypted message is then sealed in an anonymous envelope to the recipient's sealing key, so the relay cannot tell who sent it. [The security page has the details.](/security#encryption)

**Mixing.** The relay holds each message for a random few seconds, about four on average and never more than twenty, and releases everything that is due together in shuffled order. Someone watching traffic go into the relay and come out to phones cannot pair a message in with a delivery out by its timing alone. Calls being set up, SOS alerts and group membership changes are never held.

**Your mailbox.** Your phone gives each contact a pass inside your encrypted messages, and gives your relay the key it is checked with. Messages from people you have never written to share a small part of your mailbox, so a stranger with any number of accounts cannot fill it and have it refuse your contacts. Deleting a contact replaces the pass. The relay learns whether a message came from someone you wrote to, never who.

**Delivery.** A live connection delivers messages the moment the relay releases them while Clairix is open or kept online in the background. Otherwise your phone can be woken through UnifiedPush, an open push standard served by a distributor you choose. The push carries only the word "wake". There is no Google service in the path.

**Calls.** Voice calls use WebRTC between the two phones. The keys that protect the audio travel inside the encrypted envelopes, so neither the relay nor a TURN server can read or replace them. Calls connect directly when both networks allow it, otherwise through a TURN relay that forwards packets it cannot decrypt. Incoming calls ring over the lock screen like a phone call.

**Voice notes, photos and video.** Voice notes of up to thirty seconds are compressed to fit inside a single encrypted envelope and the recording is deleted from the phone as soon as it is sent. Photos and videos are split into encrypted chunks and reassembled on the recipient's phone.

**Groups.** A group is a list of members held on the phones, not on the relay. Messages fan out from your phone to each member, each one separately encrypted. The relay sees no group. Members are recognised by their keys, not their numbers, so someone who takes the same number on another relay is not taken for a member.

**Offline mesh.** Off by default. With it on, texts, group texts and SOS that cannot reach the relay are sealed into the same envelopes the relay carries and passed phone to phone over Bluetooth, hop by hop, until one reaches the person they are for. Each is addressed by a tag that changes every day instead of a number, kept for at most two days and eight hops. Every phone on the way needs the mesh on too.

**Cover traffic.** Off by default. With it on, your phone sends envelopes nobody reads to random contacts at random intervals, so your real messages are not the only traffic it sends.

**Your keyboard.** Clairix asks the keyboard not to learn from anything typed in it, and Gboard and Samsung's keyboard honour that.

**Disappearing messages.** Set a timer on any conversation or group, from 5 minutes to 4 weeks. Each message is deleted from your phone that long after you read it, and your own messages that long after they are sent, photos and voice notes with them. The other side is told and their Clairix does the same, and every message carries the timer so a phone that missed the change still deletes it. Deleted messages are overwritten in the database, not just unlinked.

**On your phone.** Your identity keys are stored under a key held in Android's hardware keystore, and the message database is encrypted. What reads your conversations needs the phone unlocked: while it is locked, messages are collected but stay sealed until you unlock, a call rings as "Clairix call" naming no one, and notifications show no names or text. A copy of the app's files taken off the phone is unreadable.

</div>

<div class="limits cham">

### What it cannot do {#comms-limits}

- It cannot reach people who do not use Clairix. A Clairix number talks only to other Clairix numbers, on your relay or on another relay yours can reach.
- It cannot hide that you use Clairix from your network operator or Wi-Fi provider. They can see that your phone connects to the relay, not what it carries.
- The relay knows the recipient of each envelope, its size and the time, and whether it came from someone the recipient has written to. A send names no account: it spends a single-use blind token, so not even a relay operator who captured every request can tell which account a send came from. Without Tor, such an operator could still see the network address each request came from. The relay never sees content.
- Both relays involved in a move see the same keys registered on each, which ties your old number to your new one for whoever can read both.
- A stranger can still use up the share of your mailbox kept for people you have never written to, holding up first messages from other new contacts for up to an hour. Your contacts are not affected.
- Calls do not go through Tor.
- Mixing is one relay holding messages for a few seconds, not a network of independent mixes. The relay's own operator still sees every envelope arrive and leave. On a quiet relay there is little to mix with, and someone watching both ends for weeks can still match people by how much they send.
- A contact on another relay needs a version of Clairix that knows about other relays. Two relays can give out the same number, and the app will not keep two contacts with one number.
- While the offline mesh is on, anyone scanning Bluetooth nearby can see that a Clairix phone is there, never whose. Only a bundle's sender and recipient can tell who it is for. Bundles between the same two people on one day carry the same tag. Photos, videos and calls do not go over the mesh.
- Calls need both phones online at the same time. If the other phone is asleep and Android has restricted Clairix in the background, the call may not ring. Clairix asks you to exempt it from battery optimisation for this reason.
- Clairix does not back up messages anywhere. If you wipe or lose the phone, the history is gone. That is deliberate.
- Disappearing messages delete Clairix's copies. They cannot stop the other person taking a screenshot, photographing the screen or copying the text into another app.

</div>
