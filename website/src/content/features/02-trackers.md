---
id: trackers
order: 2
title: "Know if you're being followed"
kicker: "Trackers"
icon: tracker
variant: radar
visualLabel: "Follower check"
visualCaption: "About 10 min · 300 m"
---

<div class="f-does">

### What it does {#trackers-does}

Clairix scans for Bluetooth trackers and unknown devices, follows them through address rotations, and confirms a device as following you only when it has moved with you. It can locate the device, identify it where the tracker supports identification, remember what rides along in your car, and show you how predictable your own routine is.

</div>

### How it works {#trackers-how}

<div class="methods">

**Recognition.** Clairix recognises the advertisements of AirTag and other Find My accessories, Tile, Samsung SmartTag and SmartTag 2, Chipolo and Chipolo Spot, Pebblebee, Orbit, Nut, Motorola tags, Invoxia and Eddystone beacons, as well as the generic Bluetooth modules used in hardwired GPS trackers. Known benign devices are filtered out so the list is not your headphones and your neighbour's television.

**Following through rotation.** Trackers change their Bluetooth address every few minutes. Clairix links the new address to the old one in two ways. First by fingerprint: the parts of the advertisement that do not change. Second by cadence: how often the device advertises, how tightly it holds that interval, its transmit power and radio features. A link is made only when exactly one candidate fits. A wrong merge would be worse than a missed one, so ambiguity stitches nothing.

**Proof of following.** "Seen five times over ten minutes" describes a tracker in your bumper, and it equally describes a Tile through a wall. Only movement separates them. Clairix records where each device was heard and confirms a follower only when the same device has been heard at places far enough apart, over long enough, while you were actually travelling. Defaults are about ten minutes and three hundred metres, and you can adjust both. Without a location fix, the stronger claim is simply never made.

**Alerts.** When a device is confirmed, Clairix posts a high-priority notification that names the device, says how far apart it was heard, and tells you where to check: wheel wells, bumper covers, the OBD port, under seats, bag linings.

**Locating.** A phone has one antenna and no sense of direction for a Bluetooth signal, but it has a compass, and your body blocks the signal. Hold the phone in front of you and turn slowly. Clairix buckets signal strength by the direction you were facing and marks the strongest direction on a compass rose. Walk that way, watching the warmer-and-colder line and the distance estimate. The estimate is honest to roughly a factor of two indoors, and the screen says "about" for that reason.

**Identification and sound.** Trackers that implement the industry standard for unwanted location tracking (the DULT accessory protocol) expose a service once they have been away from their owner for a while. Clairix connects once per device and asks it to name its maker and model, to return an obfuscated serial the maker can tie to an account under legal process, and, on your request, to play a sound. Clairix keeps only a hash of the serial. A tracker that refuses the connection is telling you something too: its owner is probably nearby.

**Trip memory.** Clairix notices when you are driving and scans intensively for the first minutes of each trip, when a tracker on a vehicle is most likely to wake. It remembers which devices were present on each trip. A device that rides along on three separate drives is in the car, even if it was too quiet to be confirmed on any one of them.

**Self-test.** Your phone can briefly advertise a tracker-like test signal so you can confirm that detection works. On some phones the Bluetooth controller cannot hear its own transmissions. The test says which case you are in rather than claiming a pass.

**Pattern of life.** From your own location history, Clairix finds the places you stay, works out which is home and which is work, measures how consistent your weekday departure times are and how often you take the same route between the same two places, and gives you a predictability score with suggestions. Everything runs on the phone. Nothing is sent anywhere.

</div>

<div class="limits cham">

### What it cannot do {#trackers-limits}

- It cannot confirm a follower without location and time. If you deny location, Clairix can list nearby devices but will never call one a follower.
- It cannot see a tracker that never advertises. Some GPS trackers use only a cellular modem and no Bluetooth. Those are invisible to a phone.
- With the screen off, Android allows a Bluetooth scan to continue only when it is filtered to known device types. Clairix uses the full unfiltered scan while the screen is on and switches to filters for known tracker brands when it goes off. An unknown, uncatalogued device is therefore most likely to be discovered while you are using the phone, and tracked after that.
- Two trackers of the same model and firmware can look identical. Clairix will report "an AirTag" rather than "the same AirTag" when it cannot tell them apart, and it says so.
- Identification over the tracker protocol works only when the tracker has been separated from its owner for a while and only on trackers that implement it.
- Distance estimates are approximate. Signal strength indoors varies by a factor of two or more for the same distance.

</div>
