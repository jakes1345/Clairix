---
id: networks
order: 5
title: "Spot fake networks, towers and GPS"
kicker: "Networks"
icon: tower
variant: tower
visualLabel: "Cell baseline"
visualCaption: "Your own history of normal"
---

<div class="f-does">

### What it does {#networks-does}

Clairix flags Wi-Fi access points that behave like bait, catches "answer everything" rogue hotspots with a test that cannot produce a false positive, watches your cellular connection against a baseline of what is normal for you, checks that your GPS signal is really coming from satellites, and tells you when 2G is allowed or your VPN is leaking.

</div>

### How it works {#networks-how}

<div class="methods">

**The canary network.** A Karma-style attack works by saying yes to every network a phone asks for. On first run, Clairix generates a random network name that exists nowhere on earth and asks Android to probe for it as a hidden network. Nobody else knows this name. So any access point that answers heard the probe and lied. There is no benign explanation, and that is what zero false positives by construction means. Adding the name is why Android may ask, the first time you start scanning, whether Clairix can suggest Wi-Fi networks. Nothing ever joins it, because no such network exists. Say no and the trap stays off; everything else still works.

**Bait rules.** Early versions of this scanner flagged every carrier hotspot and every dual-band router in the country, which teaches people to ignore it. The rules now describe only what a normal network does not do: an open network using a carrier's brand, an open twin of a secured network you can also see, and the default names of known interception and research equipment.

**Cell tower baseline.** A fake tower cannot be told from a real one by looking at the spectrum, but it does change what your modem reports: which cell serves you, on what technology, how loud, with how many neighbours. Clairix samples these every fifteen seconds while the scanner runs and builds a baseline of the cells you normally see at the places you normally are. It then scores indicators such as a downgrade to an older network technology, a cell ID that does not belong in the area it claims, an area change while you are standing still, an unusually strong signal, an unknown cell at a familiar place, a tower that lives for seconds, rapid switching between cells or technologies, a cell with no visible neighbours, and a transmitter that is physically within about eighty metres of you. Scores accumulate. One indicator is noise; several at once are a problem, and that is when you are told.

**GPS spoofing and jamming.** A spoofer is a transmitter that sends fake satellite signals; a jammer drowns the real ones out. Clairix checks what the satellites report against what real satellites look like: signal strengths that are too even or too strong, the time the satellites give against the network's time, the GPS position against the one from Wi-Fi and cell towers, jumps no vehicle could make, apps allowed to fake the location, the receiver's clock jumping, the receiver's own gain control, and a fix that collapses to one constellation. It runs while the GPS page is open, and in the background whenever something else already has the GPS on, such as navigation. Navigation shows a warning over the map when the signal is spoofed or jammed.

**2G.** 2G is how fake cell towers work: a tower that refuses 4G pushes the phone down to 2G, where the network never proves it is real. Android hides the state of the "Allow 2G" switch from apps, so Clairix says whether your phone has the switch, turns the row red if the phone is ever seen on a 2G tower, and opens the page where you turn it off.

**VPN leaks.** With a VPN up, Clairix flags DNS still going to your router or carrier, IPv6 traffic the VPN does not carry, Clairix itself left outside the VPN, "Block connections without VPN" turned off, and a proxy on the network.

**Honest about maturity.** Rules that describe standing still are only applied when the phone knows it is stationary. The screen shows how mature the baseline is and how many cells and visits it is built on, so you know how much to trust the score.

</div>

<div class="limits cham">

### What it cannot do {#networks-limits}

- No phone can prove a cell tower is fake. Clairix shows indicators and a score. Treat a high score as a reason to move, to stop sensitive conversations, or to switch to Wi-Fi calling, not as a verdict.
- The baseline starts empty. For the first days in a new place, Clairix has nothing to compare against and says so.
- Travelling looks like a lot of cell switching. Several rules are deliberately silent while you are moving or while your movement is unknown.
- On dual-SIM phones, Clairix watches the default data SIM.
- Wi-Fi rules can only see networks that broadcast. A hidden rogue network that does not answer the canary is not flagged.
- The GPS checks catch the spoofers that are cheap to build. One that simulates a full sky at the right time passes the signal checks; the comparison with Wi-Fi and cell towers is the one it cannot pass while a tower or access point is in range.
- Clairix cannot read or change the "Allow 2G" switch. It can only tell you when 2G has been used and take you to the setting.

</div>
