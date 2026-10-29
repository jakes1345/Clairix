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

Clairix turns your phone into a six-method sweep for covert cameras: lens glint, infrared, network, Wi-Fi setup hotspots, Bluetooth setup beacons and a magnetic gauge. A guided checklist walks you through a whole room. Anything found with enough confidence is logged to your timeline and shown in your notifications.

</div>

### How it works {#hidden-cameras-how}

<div class="methods">

**Lens glint.** A camera lens, even a pinhole lens, is a retroreflector. Light that goes down the barrel bounces off the sensor and comes straight back along the same line. With the torch on, Clairix watches the viewfinder for small, round, bright spots and tracks each one from frame to frame. A screw head or a glass edge flashes and slides as you pan; a lens holds its place while the phone holds still. A spot that stays within a cell or two of where it was first seen for about 0.7 seconds is then tested: the torch blinks off for a moment. A reflection goes dark with it, turns red and is logged as a finding; a light that stays on by itself, like a standby LED or a screen, is shown grey and never reported. A spot that drifts, or a scene that changes, starts the wait again. Only the brightness channel of each frame is read, and no frame is ever saved.

**Infrared.** Night-vision cameras light the room with infrared LEDs around 850 nm. You cannot see them. Most phone front cameras have a weak infrared filter and can. Switch to IR mode, make the room dark, and point the front camera at smoke detectors, clocks, chargers, outlets and vents. If the room is too bright for a dot to stand out, Clairix says so instead of pretending to look.

**Network.** Joined to the venue's Wi-Fi, Clairix sweeps the local subnet for devices that speak camera: the RTSP video ports, the recorder ports used by common surveillance brands, and web interfaces whose banner names a camera maker. It also sends an ONVIF discovery probe, which every ONVIF camera must answer, and browses mDNS for video services, including HomeKit cameras. A device with only a web page and nothing camera-like is not reported; that is every printer and router.

**Wi-Fi setup hotspots.** A camera that has not been given the venue's Wi-Fi usually broadcasts its own open hotspot with the maker's name and a few bytes of its hardware address. Clairix recognises the names used by common covert mini cameras and by mainstream brands, matching whole names so an ordinary network containing "cam" is not flagged.

**Bluetooth setup beacons.** The same idea over Bluetooth. Clairix listens for twenty seconds to every advertisement in range and reports the ones that name a camera or carry a known provisioning service.

**Magnetic gauge.** Most covert cameras are mounted with a magnet or sit inside a speaker. With the gauge on and the back of the phone flat to a surface, slide slowly along walls behind the bed, under tables and along shelves. The gauge learns the room's baseline and shows the deviation. Drywall and wood do not move the needle. If the phone's compass reports its own readings as unreliable, Clairix tells you to recalibrate rather than showing a false reading.

**Guided sweep.** Ten steps, from where to stand (the camera has to see its target, so stand where you would be exposed and look back) to the fingernail test for a two-way mirror. Each step is ticked off as you go.

</div>

<div class="limits cham">

### What it cannot do {#hidden-cameras-limits}

- It cannot see a camera that has no line of sight to you. The optical methods only work on a lens you can point the phone at.
- It cannot find a camera you did not look at. The sweep is a method, not a scanner that covers a room from one spot.
- It cannot detect analogue wireless cameras. Cheap video senders on 900 MHz, 1.2 GHz, 2.4 GHz and 5.8 GHz broadcast FM video with no Wi-Fi, Bluetooth or network. A phone's radios decode only Wi-Fi, Bluetooth, NFC and cellular. There is no spectrum analyser in a phone. The app says so on the sweep screen.
- It cannot detect a camera that only records to a memory card and transmits nothing. For that, the physical methods are the only methods.
- The network scan sees only the Wi-Fi network you are joined to. A camera on a separate network, or on mobile data, is invisible to it.
- Infrared mode needs a dark room. Lens glint mode needs a scene that is not washed out by sunlight or mirrors.

</div>
