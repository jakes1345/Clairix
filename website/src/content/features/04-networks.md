---
id: networks
order: 4
title: "Spot fake networks and towers"
kicker: "Networks"
icon: tower
variant: tower
visualLabel: "Cell baseline"
visualCaption: "Your own history of normal"
---

<div class="f-does">

### What it does {#networks-does}

Clairix flags Wi-Fi access points that behave like bait, catches "answer everything" rogue hotspots with a test that cannot produce a false positive, and watches your cellular connection against a baseline of what is normal for you.

</div>

### How it works {#networks-how}

<div class="methods">

**The canary network.** A Karma-style attack works by saying yes to every network a phone asks for. On first run, Clairix generates a random network name that exists nowhere on earth and asks Android to probe for it as a hidden network. Nobody else knows this name. So any access point that answers heard the probe and lied. There is no benign explanation, and that is what zero false positives by construction means.

**Bait rules.** Early versions of this scanner flagged every carrier hotspot and every dual-band router in the country, which teaches people to ignore it. The rules now describe only what a normal network does not do: an open network using a carrier's brand, an open twin of a secured network you can also see, and the default names of known interception and research equipment.

**Cell tower baseline.** A fake tower cannot be told from a real one by looking at the spectrum, but it does change what your modem reports: which cell serves you, on what technology, how loud, with how many neighbours. Clairix samples these every fifteen seconds while the scanner runs and builds a baseline of the cells you normally see at the places you normally are. It then scores indicators such as a downgrade to an older network technology, a cell ID that does not belong in the area it claims, an area change while you are standing still, an unusually strong signal, an unknown cell at a familiar place, a tower that lives for seconds, rapid switching between cells or technologies, a cell with no visible neighbours, and a transmitter that is physically within about eighty metres of you. Scores accumulate. One indicator is noise; several at once are a problem, and that is when you are told.

**Honest about maturity.** Rules that describe standing still are only applied when the phone knows it is stationary. The screen shows how mature the baseline is and how many cells and visits it is built on, so you know how much to trust the score.

</div>

<div class="limits cham">

### What it cannot do {#networks-limits}

- No phone can prove a cell tower is fake. Clairix shows indicators and a score. Treat a high score as a reason to move, to stop sensitive conversations, or to switch to Wi-Fi calling, not as a verdict.
- The baseline starts empty. For the first days in a new place, Clairix has nothing to compare against and says so.
- Travelling looks like a lot of cell switching. Several rules are deliberately silent while you are moving or while your movement is unknown.
- On dual-SIM phones, Clairix watches the default data SIM.
- Wi-Fi rules can only see networks that broadcast. A hidden rogue network that does not answer the canary is not flagged.

</div>
