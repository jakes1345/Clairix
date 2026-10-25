---
id: comms
order: 5
title: "Talk privately"
kicker: "Private comms"
icon: comms
variant: cipher
visualLabel: "Sealed envelope"
visualCaption: "The relay cannot open it"
---

<div class="f-does">

### What it does {#comms-does}

End-to-end encrypted messages, voice notes, photos, videos and voice calls between Clairix apps, addressed by a Clairix number instead of a phone number. Groups for a family, a team or a community. SOS alerts to everyone in your group. Contacts on other people's relays, and an offline mesh that carries texts and SOS phone to phone over Bluetooth when there is no network.

</div>

### How it works {#comms-how}

<div class="methods">

**Clairix numbers.** At registration our relay gives your phone a random nine-digit Clairix number. It is not a phone number: it cannot call or text a phone, and a phone cannot reach it. What it buys you is a handle you can give someone without giving them your real number or your identity. You can choose whether your number can be looked up. Unlisted numbers are reached only by scanning your QR code in person.

**Pairing.** Two people pair by scanning each other's QR code in person, or by entering a listed number and then comparing safety numbers. From then on each phone has the other's identity key pinned.

**Other relays.** *New in Beta 5.* Anyone can run a Clairix relay, and a contact on another one is added by scanning their QR code, which names their relay. Everything for them goes to your relay, which forwards it to theirs signed with its own key. Their relay sees your relay's address, never your phone's, and nothing in the request names you as the sender. Your relay sees which relay and which number you write to. The contact's page in the app says which relay they are on. An operator can turn this off for their relay.

**Encryption.** Every message is encrypted on your phone for one contact with the Olm double ratchet, using vodozemac, the audited Rust implementation used by Matrix. Each message uses a fresh key, so a key stolen later cannot decrypt earlier messages, and a session recovers on its own after a compromise. The encrypted message is then sealed in an anonymous envelope to the recipient's sealing key, so the relay cannot tell who sent it. [The security page has the details.](/security#encryption)

**Mixing.** *New in Beta 5.* The relay holds each message for a random few seconds, about four on average and never more than twenty, and releases everything that is due together in shuffled order. Someone watching traffic go into the relay and come out to phones cannot pair a message in with a delivery out by its timing alone. Calls being set up, SOS alerts and group membership changes are never held.

**Delivery.** A live connection delivers messages the moment the relay releases them while Clairix is open or kept online in the background. Otherwise your phone can be woken through UnifiedPush, an open push standard served by a distributor you choose. The push carries only the word "wake". There is no Google service in the path.

**Calls.** Voice calls use WebRTC between the two phones. The keys that protect the audio travel inside the encrypted envelopes, so neither the relay nor a TURN server can read or replace them. Calls connect directly when both networks allow it, otherwise through a TURN relay that forwards packets it cannot decrypt. Incoming calls ring over the lock screen like a phone call.

**Voice notes, photos and video.** Voice notes of up to thirty seconds are compressed to fit inside a single encrypted envelope and the recording is deleted from the phone as soon as it is sent. Photos and videos are split into encrypted chunks and reassembled on the recipient's phone.

**Groups.** A group is a list of members held on the phones, not on the relay. Messages fan out from your phone to each member, each one separately encrypted. The relay sees no group. From Beta 5, members are recognised by their keys, not their numbers, so someone who takes the same number on another relay is not taken for a member.

**Offline mesh.** *New in Beta 5.* Off by default. With it on, texts, group texts and SOS that cannot reach the relay are sealed into the same envelopes the relay carries and passed phone to phone over Bluetooth, hop by hop, until one reaches the person they are for. Each is addressed by a tag that changes every day instead of a number, kept for at most two days and eight hops. Every phone on the way needs the mesh on too.

**Cover traffic.** Off by default. With it on, your phone sends envelopes nobody reads to random contacts at random intervals, so your real messages are not the only traffic it sends.

**Your keyboard.** *New in Beta 5.* Clairix asks the keyboard not to learn from anything typed in it, and Gboard and Samsung's keyboard honour that.

**On your phone.** Your identity keys are stored under a key held in Android's hardware keystore. Message bodies in the local database are encrypted with the same keystore. A copy of the app's files taken off the phone is unreadable.

</div>

<div class="limits cham">

### What it cannot do {#comms-limits}

- It cannot reach people who do not use Clairix. A Clairix number talks only to other Clairix numbers, on your relay or on another relay yours can reach.
- It cannot hide that you use Clairix from your network operator or Wi-Fi provider. They can see that your phone connects to the relay, not what it carries.
- The relay knows the recipient of each envelope, its size and the time. A send names no account: it spends a single-use token your phone fetched earlier. A relay operator who captured live requests could still see the network address each came from, and which account fetched a batch of tokens. Use Tor or a VPN if your address matters. The relay never sees content.
- Mixing is one relay holding messages for a few seconds, not a network of independent mixes. The relay's own operator still sees every envelope arrive and leave. On a quiet relay there is little to mix with, and someone watching both ends for weeks can still match people by how much they send.
- A contact on another relay needs a version of Clairix that knows about other relays. Two relays can give out the same number, and the app will not keep two contacts with one number.
- While the offline mesh is on, anyone scanning Bluetooth nearby can see that a Clairix phone is there, never whose. Anyone who already has a person's public keys, such as their contacts, can tell when a bundle being carried is for that person. Photos, videos and calls do not go over the mesh.
- Calls need both phones online at the same time. If the other phone is asleep and Android has restricted Clairix in the background, the call may not ring. Clairix asks you to exempt it from battery optimisation for this reason.
- Clairix does not back up messages anywhere. If you wipe or lose the phone, the history is gone. That is deliberate.

</div>
