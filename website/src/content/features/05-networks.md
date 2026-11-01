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

Clairix flags Wi-Fi access points that behave like bait, catches "answer everything" rogue hotspots with a made-up network name only they answer to, watches your cellular connection against a baseline of what is normal for you, checks that your GPS signal is really coming from satellites, and tells you when 2G is allowed or your VPN is leaking.

</div>

### How it works {#networks-how}

<div class="methods">

**The canary network.** A Karma-style attack works by saying yes to every network a phone asks for. While the scanner runs, Clairix makes up a network name and asks Android to probe for it as a hidden network. The name looks like a home router's, a word and a few digits drawn from tens of millions of combinations, and it is replaced every time the scanner starts and every six hours, so the probe does not announce Clairix and does not tag the phone for longer than a few hours. Nobody else knows the name, so an access point that answers it heard the probe and lied; an ordinary network never does that. Old names stay on the watch list for a week, because answer-everything kit keeps the names it hears. Adding the name is why Android may ask, the first time you start scanning, whether Clairix can suggest Wi-Fi networks; the trap counts as armed only once you have allowed it, and the Wi-Fi tab says which. The name is added as an untrusted network with auto-join off and a random password nobody knows, so the phone does not join whatever answers. When the scanner stops, the name is withdrawn and the phone stops asking for it. Say no and the trap stays off; everything else still works.

**Bait rules.** Early versions of this scanner flagged every carrier hotspot and every dual-band router in the country, which teaches people to ignore it. Besides the canary, only two things are flagged as bait, because only they have no innocent explanation: an open twin of a secured network you can also see, and the default names of Wi-Fi interception kit (a Pineapple, hak5). An open network carrying a carrier's name — xfinitywifi, attwifi, BTWifi-with-FON — is what those operators broadcast by design, so it is noted in the Wi-Fi list at the lowest level, as an open captive portal to use a VPN on. It is not called bait, goes on no timeline and plays no part in any correlation.

**Cell tower baseline.** A fake tower cannot be told from a real one by looking at the spectrum, but it does change what your modem reports: which cell serves you, on what technology, how loud, with how many neighbours. Clairix samples these while the scanner runs, every fifteen seconds while the phone is in use or on the move, every thirty with the screen off and once a minute once it has settled, and builds a baseline of the cells you normally see at the places you normally are. It then scores indicators such as a downgrade to an older network technology, a cell ID that does not belong in the area it claims, an area change while you are standing still, an unusually strong signal, an unknown cell at a familiar place, a tower that lives for seconds, rapid switching between cells or technologies, a cell with no visible neighbours, and a transmitter that is physically within about eighty metres of you. Scores accumulate. One indicator is noise; several at once are a problem, and that is when you are told. The rules remember context as well as readings: a downgrade, a technology switch, an area change or a short-lived cell during a voice call, or in the minutes after one, is not counted, because a phone without VoLTE drops to 3G or 2G for every call; a cell that reads eighty metres from where you sit day after day, as a rooftop or small cell does, stops being news once it has done so over a full day; and a cell whose area code changes for good, as happens when a carrier re-plans, is accepted after it has reported the new area consistently for an hour instead of being flagged forever.

**GPS spoofing and jamming.** A spoofer is a transmitter that sends fake satellite signals; a jammer drowns the real ones out. Clairix checks what the satellites report against what real satellites look like: signal strengths that are too even or too strong, the time the satellites give against the network's time, the GPS position against the one from Wi-Fi and cell towers, jumps no vehicle could make, apps allowed to fake the location, the receiver's clock jumping, the receiver's own gain control, and a fix that collapses to one constellation. It runs while the GPS page is open, and in the background whenever the GPS is already on for something else: the scanner's own position fixes while you are moving, navigation, another app. In the background it does not switch the satellites on, so the two checks that need the receiver's raw measurements, its clock and its gain control, have data only when another app has asked for them; Android 12 cannot share them that way, so there Clairix asks for them itself, only while you are moving. In the background, the Wi-Fi and cell position it compares against is asked for only while satellite positions are coming in: once a minute on the move, every fifteen minutes at rest. Navigation shows a warning over the map when the signal is spoofed or jammed. One check failing on its own reads as unusual, not as spoofed: the verdict needs two checks to fail, a failing check with a warning from the receiver itself beside it (signals too loud, GPS alone, its clock jumping, its gain control moving), a flat spread that persists, a second impossible jump, or Android's own word that an app is faking the location. When the satellites and the Wi-Fi and cell position disagree, Clairix says so and does not tell you to trust either, because a mis-mapped access point or train Wi-Fi mapped to the depot looks exactly like a spoofer from the phone's side.

**2G.** 2G is how fake cell towers work: a tower that refuses 4G pushes the phone down to 2G, where the network never proves it is real. Android hides the state of the "Allow 2G" switch from apps, so Clairix goes by what it can see. Phone check → Health says whether your phone has the switch, turns the row red when the phone has used a 2G tower in the past week, and opens the page where you turn the switch off. Tell Clairix you have turned it off and the row says so, keeping the date it last saw 2G as history; it turns red again only if the phone uses a 2G tower after that. Where a device policy or Android 16's Advanced Protection blocks 2G, the row says that instead. On Android 14 and later a second row points to Android's own protections, which no app can read: a setting that refuses unencrypted connections and, from Android 15, a warning when a network turns encryption off or asks for the phone's identity, on phones whose modem supports them.

**VPN leaks.** With a VPN up, Clairix flags DNS still going to your router or carrier, IPv6 traffic the VPN does not carry, Clairix itself left outside the VPN, "Block connections without VPN" turned off, and a proxy on the network. The Wi-Fi tab's VPN row says whether Clairix's own traffic goes through the VPN, not merely whether one is running, since a VPN can be set to carry only some apps.

**Honest about maturity.** Rules that describe standing still are only applied when the phone knows it is stationary. The screen shows how mature the baseline is and how many cells and visits it is built on, so you know how much to trust the score.

</div>

<div class="limits cham">

### What it cannot do {#networks-limits}

- No phone can prove a cell tower is fake. Clairix shows indicators and a score. Treat a high score as a reason to move, to stop sensitive conversations, or to switch to Wi-Fi calling, not as a verdict.
- The baseline starts empty. For the first days in a new place, Clairix has nothing to compare against and says so.
- Travelling looks like a lot of cell switching. Several rules are deliberately silent while you are moving or while your movement is unknown.
- On dual-SIM phones, Clairix watches the default data SIM.
- Wi-Fi rules can only see networks that broadcast. A hidden rogue network that does not answer the canary is not flagged.
- The canary works only while the scanner runs and once you have allowed Clairix to suggest networks. Whether the phone sends the probe for a network it is told never to join is up to its Wi-Fi software, and Clairix cannot see its own probes.
- The GPS checks catch the spoofers that are cheap to build. One that simulates a full sky at the right time passes the signal checks; the comparison with Wi-Fi and cell towers is the one it cannot pass while a tower or access point is in range. That comparison cuts both ways: a Wi-Fi position that is confidently wrong fails it just the same, so a disagreement on its own is reported as unusual, with neither position vouched for, rather than as spoofing.
- Clairix cannot read or change the "Allow 2G" switch, or Android's own cell network protections. It tells you when 2G has been used, takes you to the setting, and takes your word that the switch is off until the phone uses 2G again.

</div>
