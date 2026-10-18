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

End-to-end encrypted messages, voice notes, photos, videos and voice calls between Clairix apps, addressed by a Clairix number instead of a phone number. Groups for a family, a team or a community. SOS alerts to everyone in your group.

</div>

### How it works {#comms-how}

<div class="methods">

**Clairix numbers.** At registration our relay gives your phone a random nine-digit Clairix number. It is not a phone number: it cannot call or text a phone, and a phone cannot reach it. What it buys you is a handle you can give someone without giving them your real number or your identity. You can choose whether your number can be looked up. Unlisted numbers are reached only by scanning your QR code in person.

**Pairing.** Two people pair by scanning each other's QR code in person, or by entering a listed number and then comparing safety numbers. From then on each phone has the other's identity key pinned.

**Encryption.** Every message is encrypted on your phone for one contact with the Olm double ratchet, using vodozemac, the audited Rust implementation used by Matrix. Each message uses a fresh key, so a key stolen later cannot decrypt earlier messages, and a session recovers on its own after a compromise. The encrypted message is then sealed in an anonymous envelope to the recipient's sealing key, so the relay cannot tell who sent it. [The security page has the details.](/security#encryption)

**Delivery.** A live connection delivers messages the moment they are queued while Clairix is open or kept online in the background. Otherwise your phone can be woken through UnifiedPush, an open push standard served by a distributor you choose. The push carries only the word "wake". There is no Google service in the path.

**Calls.** Voice calls use WebRTC between the two phones. The keys that protect the audio travel inside the encrypted envelopes, so neither the relay nor a TURN server can read or replace them. Calls connect directly when both networks allow it, otherwise through a TURN relay that forwards packets it cannot decrypt. Incoming calls ring over the lock screen like a phone call.

**Voice notes, photos and video.** Voice notes of up to thirty seconds are compressed to fit inside a single encrypted envelope and the recording is deleted from the phone as soon as it is sent. Photos and videos are split into encrypted chunks and reassembled on the recipient's phone.

**Groups.** A group is a list of members held on the phones, not on the relay. Messages fan out from your phone to each member, each one separately encrypted. The relay sees no group.

**On your phone.** Your identity keys are stored under a key held in Android's hardware keystore. Message bodies in the local database are encrypted with the same keystore. A copy of the app's files taken off the phone is unreadable.

</div>

<div class="limits cham">

### What it cannot do {#comms-limits}

- It cannot reach people who do not use Clairix. A Clairix number talks only to other Clairix numbers on the same relay.
- It cannot hide that you use Clairix from your network operator or Wi-Fi provider. They can see that your phone connects to the relay, not what it carries.
- The relay knows the recipient of each envelope and, at the moment of sending, the sender who signed the request. It does not store the sender with the envelope, but a relay operator who logged every request could see who sent to whom and when. It never sees content.
- Calls need both phones online at the same time. If the other phone is asleep and Android has restricted Clairix in the background, the call may not ring. Clairix asks you to exempt it from battery optimisation for this reason.
- Clairix does not back up messages anywhere. If you wipe or lose the phone, the history is gone. That is deliberate.

</div>
