---
id: trackers
order: 3
title: "Know if you're being followed"
kicker: "Trackers"
icon: tracker
variant: radar
visualLabel: "Follower check"
visualCaption: "About 10 min · 300 m"
---

<div class="f-does">

### What it does {#trackers-does}

Clairix scans for Bluetooth trackers and unknown devices, follows them through address rotations, and confirms a device as following you only when it has moved with you. It can locate the device, identify it where the tracker supports identification, remember what rides along on your journeys, and show you how predictable your own routine is.

</div>

### How it works {#trackers-how}

<div class="methods">

**Recognition.** Clairix recognises the advertisements of AirTag and other Find My accessories, Tile, Samsung SmartTag (all generations), Chipolo, Pebblebee, Google Find Hub tags, any tracker announcing itself under the industry's unwanted-tracking standard (DULT), Orbit, Nut, Invoxia, Eddystone beacons and tags in Apple's iBeacon format, which cheap anti-lost tags use and which never change their address, as well as the generic Bluetooth modules used in hardwired GPS trackers. A Find My item and a Find Hub tag also say whether they have been away from their owner for a while, and Clairix reads that. Known benign devices are filtered out so the list is not your headphones and your neighbour's television.

**Following through rotation.** Trackers change their Bluetooth address every few minutes. Clairix links the new address to the old one in two ways. First by fingerprint: the parts of the advertisement that do not change. Second by cadence: how often the device advertises, how tightly it holds that interval, its transmit power and radio features. A link is made only when exactly one candidate fits. A wrong merge would be worse than a missed one, so ambiguity stitches nothing.

**Proof of following.** "Seen five times over ten minutes" describes a tracker in your bumper, and it equally describes a Tile through a wall. Only movement separates them. Clairix records where each device was heard and confirms a follower only when the same device has been heard, more than once, at places far enough apart, over long enough, and only when your own position shows you travelled between those places: a position fix that jumps while you sit still, or one more than a minute old, does not count. Defaults are about ten minutes and three hundred metres, and you can adjust both. Without a location fix, the stronger claim is simply never made. A recognised tracker away from its owner that passes this test is reported as following you. Anything else that passes it is reported as travelling with you, not as a tracker: a phone, a watch or a car's own electronics look the same.

**Alerts.** When a recognised tracker is confirmed, Clairix posts a high-priority notification at once that names the device, says how far apart it was heard, and tells you where to check: wheel wells, bumper covers, the OBD port, under seats, bag linings. Anything else that travelled with you is not announced device by device in the middle of a journey: it is listed as soon as it is confirmed, and summed up in one notice when the journey ends, when the phone comes to rest or the drive is over. A tag that rode along near its owner's phone and then separates is announced the moment it does. On the Trackers screen, a tracker not heard for half an hour is shown as having followed you earlier, with when it was last heard, rather than as on you now.

**Your own devices, and one you have found.** Mark a device as yours and it leaves the threat level. The mark follows the device rather than one of its addresses: when Clairix links a new address to a device you marked, that address is yours too. Two units of one product can look alike, so a device that changes its address by design is trusted for a day at a time and then Clairix asks again; one with a fixed address, such as a car or headphones, stays trusted until you take it back. Once you have found and removed a tracker, I found it stops it counting as on you. It stays in the timeline and the evidence report, and if it is heard again after an hour away, Clairix watches it afresh and tells you if it follows you again.

**Locating.** A phone has one antenna and no sense of direction for a Bluetooth signal, but it has a compass, and your body blocks the signal. Hold the phone in front of you and turn slowly. Clairix buckets signal strength by the direction you were facing and marks the strongest direction on a compass rose. Walk that way, watching the warmer-and-colder line and the distance estimate. The estimate is honest to roughly a factor of two indoors, and the screen says "about" for that reason. You do not have to watch: TalkBack reads out the distance and the strongest direction, and says warmer or colder as you move, and the phone pulses faster as the signal grows stronger. A click with each pulse is there too, off unless you turn it on, because anyone nearby can hear it. When Clairix links a new address to the device, the locator follows it without starting over.

**Identification and sound.** Trackers that implement the industry standard for unwanted location tracking (the DULT accessory protocol) expose a service once they have been away from their owner for a while. Clairix asks such a tracker to name its maker and model and to return an obfuscated serial the maker can tie to an account under legal process: once it has stayed near you for a while or is confirmed as following, again as soon as it is seen to separate from its owner, every fifteen minutes while it stays and has not answered (up to six attempts of its own), and whenever you tap Ask it who it is in the locator, which shows when it last asked and what happened. Clairix keeps only a hash of the serial. A tracker that refuses the connection is telling you something too: its owner is probably nearby. A tracker that has answered can be asked to play a sound; before it does, Clairix warns that anyone near the tracker will hear it, including whoever put it there.

**Trip memory.** Clairix notices when you travel at road speed and scans intensively for the first minutes of each journey, when a tracker on a vehicle is most likely to wake. It remembers which devices were present for the length of each journey, and reports one that rides along on three separate journeys, even if it was too quiet to be confirmed on any one of them. A bus or a train is a journey too, so how it says so depends on what shows the journey was in your car: with your car's hands-free connected or Drive mode on, the device has been in your vehicle and you are told where to search; without either, it has been on three of your journeys, which the vehicle's own electronics or someone who travels the same way explain as well, and Clairix says that.

**All day on one charge.** The scan is built to run from morning to night. Satellite positioning runs only while you are moving: once the phone has stayed in one place for four minutes it switches to an occasional Wi-Fi or cell position (an occasional satellite one with Satellite-only positioning on, or on a phone with no Wi-Fi or cell location provider), and the phone's motion sensor brings satellites back the moment you pick it up and go. Positions come from Google Play services where the phone has it, and from Android's own location service where it does not. With the screen off, the Bluetooth controller listens only for advertisements that can be trackers or roadside surveillance hardware, and for the devices Clairix is already watching, and hands them over in batches, so the phone is not woken by every phone and pair of earbuds nearby. Once the phone has settled, it listens at low power and hands them over once a minute (unless Aggressive scan is on), and the device list is rebuilt only when something was heard or has dropped out. Checks that look at things that rarely change run less often when they can: the phone's own settings every five minutes while the screen is off, the Wi-Fi around you every ten minutes once the phone has settled with the screen off.

**Self-test.** Your phone can briefly advertise as a separated AirTag so you can confirm that the scanner hears it and that the tracker signatures recognise it as one. It does not test the follow check, which needs you to travel. On some phones the Bluetooth controller cannot hear its own transmissions. The test says which case you are in rather than claiming a pass.

**Bluetooth exposure.** The same radios work the other way too: a receiver by the road or in a shop logs every Bluetooth address it hears. The phone itself is hard to follow, because Android changes the address it advertises on every quarter of an hour, but the name can give you away and your accessories do not rotate at all. A car's hands-free, most earbuds and many watches keep one address for life. The Bluetooth exposure page shows the phone's name and replaces a personal one with the model name in a tap, warns if the phone has been left discoverable, and lists every paired accessory with whether its address is fixed and which of them were connected on your drives. It also lists the surveillance hardware the radios have heard: a Flock Safety camera's battery pack, a Raven gunshot detector or a Flock setup hotspot, matched against the map's plate readers or flagged as one the map does not have.

**Ultrasonic beacons.** Some advertising kits play tones too high for you to hear from TVs, shops and web pages, so that an app on your phone can hear them and tie your phone to the place or the programme. Clairix listens on demand for steady and keyed tones between 17.5 and 21.5 kHz. First it plays a 19 kHz tone and listens for it, because a quiet result means nothing from a microphone that cannot hear that band. Nothing is recorded: the microphone feeds a spectrum, and the spectrum is thrown away.

**Pattern of life.** From your own location history, Clairix finds the places you stay, works out which is home and which is work, measures how consistent your weekday departure times are and how often you take the same route between the same two places, and gives you a predictability score with suggestions. Everything runs on the phone. Nothing is sent anywhere.

</div>

<div class="limits cham">

### What it cannot do {#trackers-limits}

- It cannot confirm a follower without location and time. If you deny location, Clairix can list nearby devices but will never call one a follower.
- It does not use a position an app invents. While an app supplies mock locations (Developer options, Select mock location app), the scanner treats the phone's position as unknown and says so: following can be neither confirmed nor ruled out until that app is turned off.
- It cannot see a tracker that never advertises. Some GPS trackers use only a cellular modem and no Bluetooth. Those are invisible to a phone.
- With the screen off, Android allows a Bluetooth scan to continue only when it is filtered. Clairix uses the full unfiltered scan while the screen is on. When it goes off, it switches to filters for known tracker types, plus a filter on the address of each device it is already watching that those would miss, up to sixteen: one confirmed following you or keeping a pattern (about ten minutes by default), or a GPS module recognised by its name. An unknown, uncatalogued device is therefore most likely to be discovered while you are using the phone; once it is being watched, it keeps its own filter while it keeps its address.
- Two trackers of the same model and firmware can look identical. Clairix will report "an AirTag" rather than "the same AirTag" when it cannot tell them apart, and it says so.
- Identification over the tracker protocol works only when the tracker has been separated from its owner for a while and only on trackers that implement it.
- Distance estimates are approximate. Signal strength indoors varies by a factor of two or more for the same distance.
- It cannot switch Bluetooth off, unpair a device or change an accessory's address: Android keeps those for its own Settings. It can rename the phone and tell you which accessory is the fixed one.
- The ultrasound check runs only while its page is open, never in the background, and only finds tones in the band phone microphones can hear.
- Flock's plate readers upload over LTE and most have had their Bluetooth turned down since 2025, so hearing nothing next to one is normal. A phone also cannot see the Wi-Fi probe requests the newest detectors key on. The map list is the better guide; the radio is a second opinion.

</div>
