---
id: hidden-cameras
order: 2
title: "Find hidden cameras"
kicker: "Hidden cameras"
icon: lens
variant: lens
visualLabel: "Lens glint"
visualCaption: "Holds still. Turns red."
---

<div class="f-does">

### What it does {#hidden-cameras-does}

Clairix turns your phone into a six-method sweep for covert cameras: lens glint, infrared, network, Wi-Fi setup hotspots, Bluetooth setup beacons and a magnetic gauge. A guided checklist walks you through a whole room. Anything found with enough confidence is logged to your timeline and shown in your notifications. Findings and the checklist are kept, encrypted, until you clear them, and a red finding can be kept as a photo with the time and place written on it.

</div>

### How it works {#hidden-cameras-how}

<div class="methods">

**Lens glint.** A camera lens, even a pinhole lens, is a retroreflector. Light that goes down the barrel bounces off the sensor and comes straight back along the same line. With the torch on, Clairix watches the viewfinder for small, round, bright spots and tracks each one from frame to frame. A screw head or a glass edge flashes and slides as you pan; a lens holds its place while the phone holds still. A spot that stays within a cell or two of where it was first seen for about 0.7 seconds is then tested: the torch goes off, and once the picture shows it dark (or the camera confirms it), a reflection has gone dark with it, turns red and is logged as a finding; a light that stays on by itself, like a standby LED or a screen, is shown grey and not reported, and is tested again if you keep holding on it. A phone whose camera has no torch cannot run the test, and the glint mode says so instead of looking. A spot that drifts, or a scene that changes, starts the wait again. It works best within about a metre of the surface: for ceiling fixtures, stand on a chair or use the 2× zoom. Only the brightness channel of each frame is read, and no frame is saved unless you press Keep a photo on a red finding: that one picture, with the circle, the time and your position written on it, is kept encrypted on the phone and attached to the finding on your timeline.

**Infrared.** Night-vision cameras light the room with infrared LEDs around 850 nm. You cannot see them. Some phone front cameras pass enough infrared to show it, so test yours first: IR mode has a check that watches for a TV remote's flashing and remembers whether this phone has passed it, and until it has, the screen says an empty result means nothing. Then make the room dark and point the front camera at smoke detectors, clocks, chargers, outlets and vents. A point of light that holds still comes with a question: if you can see it with your own eyes it is an ordinary LED and is dropped; if you cannot, it is logged as a possible night-vision illuminator. Cameras sold as "no-glow" use 940 nm LEDs that most phone cameras cannot see at all, so an empty IR result does not clear them. If the room is too bright for a dot to stand out, Clairix says so instead of pretending to look.

**Network.** Joined to the venue's Wi-Fi, Clairix sweeps the local subnet for devices that speak camera: the RTSP video ports, the recorder ports used by common surveillance brands, and web interfaces whose banner names a camera maker. It also sends an ONVIF discovery probe, which most ONVIF cameras answer (a setting some brands ship switched off), and browses mDNS for video services, including HomeKit cameras. A device with only a web page and nothing camera-like is not reported; that is every printer and router. When the sweep could not look, it says "Sweep inconclusive" and why, instead of "nothing found": only the router answered (a network that hides devices from each other, as most hotel and guest Wi-Fi does), the network is wider than the block of 254 addresses around the phone that it checks (it says how wide), or the Wi-Fi gave the phone no IPv4 address.

**Wi-Fi setup hotspots.** A camera that has not been given the venue's Wi-Fi usually broadcasts its own open hotspot with the maker's name and a few bytes of its hardware address. Clairix recognises the names used by common covert mini cameras and by mainstream brands, matching whole names so an ordinary network containing "cam" is not flagged.

**Bluetooth setup beacons.** The same idea over Bluetooth. Clairix listens for twenty seconds to every advertisement in range and reports the ones that name a camera or carry a known provisioning service.

**Magnetic gauge.** Most covert cameras are mounted with a magnet or sit inside a speaker. With the gauge on and the back of the phone flat to a surface, slide slowly along walls behind the bed, under tables and along shelves. The gauge learns the room's baseline and shows the deviation. Drywall and wood do not move the needle. Where the phone offers it, the gauge takes the phone's own compass correction once, when it starts, and holds it, so a calibration update does not jump the needle and turning the phone does not move it; any calibration change starts the baseline afresh; and a step that stays, like sliding onto a steel stud or a door frame, settles back to quiet as the gauge learns the new level. If the phone's compass reports its own readings as unreliable, Clairix pauses the gauge and tells you to recalibrate rather than showing a false reading.

**Guided sweep.** Eleven steps, from where to stand (the camera has to see its target, so stand where you would be exposed and look back) to the fingernail test for a two-way mirror, naming the disguises sold for bedrooms and bathrooms: light bulbs, coat and towel hooks, routers and TV boxes, soundbars, thermostats, peepholes and shower heads. Each step is ticked off as you go.

</div>

<div class="limits cham">

### What it cannot do {#hidden-cameras-limits}

- It cannot see a camera that has no line of sight to you. The optical methods only work on a lens you can point the phone at.
- It cannot find a camera you did not look at. The sweep is a method, not a scanner that covers a room from one spot.
- It cannot detect analogue wireless cameras. Cheap video senders on 900 MHz, 1.2 GHz, 2.4 GHz and 5.8 GHz broadcast FM video with no Wi-Fi, Bluetooth or network. A phone's radios decode only Wi-Fi, Bluetooth, NFC and cellular. There is no spectrum analyser in a phone. The app says so on the sweep screen.
- It cannot detect a camera that only records to a memory card and transmits nothing. For that, the physical methods are the only methods.
- The network scan sees only the Wi-Fi network you are joined to. A camera on a separate network, or on mobile data, is invisible to it, and on a network that hides devices from each other it cannot see any other device; it says so when only the router answers.
- Most consumer cameras (Wyze, Blink, Ring, Arlo, Nest, many cheap "IP camera" apps) talk only to their maker's cloud and run no local service. They are invisible to the network sweep even on the same Wi-Fi; the optical, wireless and hands-on steps are what find them.
- Cameras sold as "no-glow" use 940 nm LEDs that most phone cameras cannot see at all. An empty IR result does not clear them; the physical steps do.
- A lens behind a dark plastic window throws back no visible light, so lens glint cannot see it. Dark glossy panels on clocks, detectors and chargers get the IR view in the dark and a close look by hand.
- Infrared mode needs a dark room. Lens glint mode needs a scene that is not washed out by sunlight or mirrors, and a camera with a torch.
- None of the six methods has yet been measured against named cameras, illuminators and LEDs on a test bench, so there is no detection or false-alarm rate to quote. The code is tested on synthetic frames and on the formats cameras send; [how the bench run will be done, and the table its results go in](https://github.com/jakes1345/Clairix/blob/main/docs/CAMERA-SWEEP-VALIDATION.md), are public, and the table is empty until it has been done.

</div>
