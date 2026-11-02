# Clairix on LoRa

Clairix's own long-range radio. It is not Meshtastic and does not talk to it.
A cheap LoRa board carries Clairix messages radio to radio, kilometres at a
time, with no phone network and no internet. It works in two ways:

- **A radio for a phone.** The phone pairs with a board over Bluetooth. The
  board then carries the same sealed envelopes as the relay and the Bluetooth
  mesh, so ordinary Clairix messages and SOS reach people whose phones have a
  radio too. It also carries short **Lite** messages to people who carry a
  handheld instead of a phone.
- **A handheld on its own.** The LilyGO T-Deck runs the firmware as a
  standalone "burner" messenger, with its own keyboard, screen and GPS on the
  Plus. It sends Lite texts and SOS to the people it has paired with.

The code is in `lora/core`, the protocol library tested on the host, and in
`lora/firmware`, the board glue (PlatformIO). The phone side is in
`android/app/src/main/java/app/clairix/comms/lora` and `LoraScreen.kt`.

## Boards

| Board | Role | Screen | Bluetooth pairing |
|---|---|---|---|
| Heltec WiFi LoRa 32 V3 | radio for a phone | 0.96" OLED | six-digit passkey shown on the screen |
| Seeed XIAO ESP32S3 + Wio-SX1262 | smallest radio for a phone | none | Just Works: no digits, so pair it where nobody else is in Bluetooth range |
| LilyGO T-Deck / T-Deck Plus | handheld, and a radio for a phone | 2.8" LCD and keyboard | six-digit passkey on the screen |

Build all three with `pio run -d lora/firmware`. Flash one with
`pio run -d lora/firmware -e heltec_v3 -t upload`. CI builds them on every
change (`.github/workflows/lora.yml`) and keeps the images.

## Where it transmits, and why that is legal

United States only, 902 to 928 MHz, under FCC Part 15.247(a)(2) (digital
modulation). Every preset uses 500 kHz of bandwidth, which is what lets an
unlicensed transmitter run without frequency hopping and without a duty-cycle
limit. Power is 2 to 22 dBm, the SX1262's maximum, well under the 30 dBm the
rule allows. The 15 channels (915.5 to 922.5 MHz) sit between LoRaWAN's US
uplink and downlink channels.

Nothing touches an amateur band. Amateur rules forbid encryption meant to hide
a message's meaning, and Clairix encrypts everything. It does not jam and does
not imitate any other system: its sync word is the SX126x private one, not
LoRaWAN's or Meshtastic's.

| Preset | Spreading factor | Bit rate | A full 255-byte frame on the air |
|---|---|---|---|
| Fast | 8 | 12.5 kbit/s | 181 ms |
| Balanced (default) | 10 | 3.9 kbit/s | 590 ms |
| Long range | 12 | 1.2 kbit/s | 1993 ms |

Every radio on a network must use the same preset, or they cannot hear each other.

## The cryptography, exactly

**On the air (every frame).** A network key of 32 bytes is stretched with
HKDF-SHA512 into an encryption key, a MAC key and an 8-byte network id. Each
frame is:

    hint(1) | nonce(8) | ChaCha20 ciphertext | tag(8)

- The ciphertext is ChaCha20 (RFC 8439) under the encryption key.
- The tag is the first 8 bytes of HMAC-SHA512 over the nonce and ciphertext,
  under the MAC key. A radio that does not hold the key cannot read or forge
  a frame. A forged frame is dropped before anything reads it.
- The hint is the first byte of HMAC-SHA512(network id, nonce). It is different
  on every frame, so frames carry no fixed radio or network ID that a
  listener could follow. A radio uses it to skip other networks' frames cheaply.
- The nonce holds the network time in seconds and 30 random bits. It is sent
  in the clear: a listener sees roughly when each frame was sent, which they
  could tell anyway by hearing it.
- The channel changes every ten minutes, to one of the 15, chosen from the key.

**The public network and private ones.** With no network set, every Clairix
radio shares the public key, SHA-512("clairix-lora public network v1"). Any
Clairix radio can then relay your frames, and any of them can read the
routing inside, but never a message. A private network is a fresh random key,
shared as a 55-character code in person or over Clairix. To radios without the
code, its frames are noise.

**Ordinary Clairix messages.** These travel as the same bundles the Bluetooth
mesh carries: a sealed envelope (Olm session, sealed sender) under a routing
tag that only the sender and recipient can compute. The envelope is cut into
fragments of up to 226 bytes, 10 at most, so 2239 bytes at most. Each fragment
is flooded on its own. A bundle is checked against its id, the first 8 bytes of
SHA-512(tag, hour, envelope), before it is accepted, so a stray or forged
fragment can delay a message but not change it. Relays on the way see a tag
they cannot link to anyone, and an envelope they cannot open.

**Lite messages.** Lite messages are short (145 bytes of text) and fit one
frame. They go between people who paired over the air. Each person has an
X25519 key pair. Sealing to a recipient R uses a fresh ephemeral key:

- k1 = HKDF-SHA512(X25519(e, R)) hides the sender's key with ChaCha20.
- k2 = HKDF-SHA512(X25519(e, R) || X25519(s, R)) seals the message with
  ChaCha20-Poly1305.

Only R can see who sent it, and R opens only messages from its contacts.
Messages are padded to 32, 64, 96 or 128 bytes, so most are the same size.

**Lite has no forward secrecy.** Whoever later gets R's secret key, or the
sender's, can open every Lite message between them that they recorded off the
air. Use full Clairix messages, phone to phone, when that matters.

**Pairing over the air.** One side commits to its key and a random nonce,
using SHA-512. The other side answers with its own key and nonce, then the
first side reveals. Both screens show six digits made from both keys and both
nonces. Pair only if the digits match. Someone sitting in between has to
commit before seeing the second nonce, so a forged pairing shows matching
digits one time in a million. Pairing frames go one hop only.

**Phone to radio.** The phone and the radio talk over Bluetooth LE. The radio
lets in only a bonded phone, and its characteristic needs an encrypted link.
On boards with a screen, bonding asks for the six digits the radio shows. The
XIAO has no screen, so it pairs with Just Works. That protects the link from
anyone who only listens, but not from someone in Bluetooth range who sits in
between while you pair.

**The handheld's storage.** The T-Deck keeps its Lite key, contacts, messages
and settings in one file on flash. The file is sealed with XChaCha20-Poly1305
under a key derived from your PIN with Argon2id. The board has no secure
element, so the PIN's strength and Argon2's cost are all that protect it. Too
many wrong PINs wipe the vault, and you can wipe it at any time. Its radio
starts only after it is unlocked.

## What it does not hide

- **That a radio is transmitting, roughly how much, and from where.** No
  encryption hides that. Direction finding can locate any transmitter, this
  one included. Several people with directional antennas, or one person
  moving around, can find it.
- What makes that harder:
  - lower power;
  - short frames: the Fast preset sends a 255-byte frame in under 0.2 s;
  - relays, so your message leaves from other radios as well as your own;
  - the hint that changes on every frame;
  - the channel that changes every ten minutes.

  These make tracking harder, not impossible.
- **The network key on the Heltec and XIAO.** These radios keep the key in
  their flash unencrypted. Anyone who takes the radio can read the network's
  routing, though never a message. Make a new private network if one is lost.
- **Bluetooth.** A phone or radio with Bluetooth on can be seen by anyone
  scanning nearby. While no phone is connected, the radio advertises so that
  its phone can come back. During the two-minute pairing window it
  advertises with its service and the name "Clairix radio". The rest of the
  time it sends an empty advertisement once a second. Both come from a
  private address that changes, where the board's Bluetooth allows it. If
  the board refuses, the radio falls back to its fixed address and says so on
  its serial port.
- **Timing.** The time in each frame's nonce is in the clear, as is a frame's size.

## The phone-only mesh

Without any radio, phones still carry messages to each other:

- **Bluetooth LE**, every phone with the mesh on. On phones whose Bluetooth has
  the long-range (LE Coded) mode, the mesh also advertises and listens on it
  at full power. A coded signal reaches several times as far, at an eighth of
  the speed. This was already in the mesh (`MeshNode.kt`). The Offline mesh
  page shows whether it is on.
- **Wi-Fi Aware**, on Android 13 and later on phones that have it. Each phone
  publishes the mesh service under a random id that changes every time the
  mesh starts. Of two phones that find each other, the one with the lower id
  offers a meeting with a fresh passphrase. They open a Wi-Fi Aware data path
  protected with it, and run the same exchange as over Bluetooth, much faster.
  It needs the Nearby Wi-Fi devices permission. That permission is declared
  never for location, and the mesh runs on Bluetooth without it.

## Testing on hardware

The protocol is covered by the host tests and the known answers, and the
phone checks the same answers. The board glue and the radios themselves can
only be tested on the boards. Before calling this done, check:

1. **Heltec V3:** flash it. The screen shows the radio's name, and the
   battery reading is right on battery and reads as USB power on USB. The
   ADC control pin's polarity differs between board revisions.
2. **Pairing a radio:** press the button once and pair from Offline mesh >
   Radio. Android asks for the six digits on the screen. Then check:
   - a Bluetooth scanner app shows the radio's address changing over time;
     if it stays fixed, the board refused the private address;
   - after a reboot of either side, the phone reconnects on its own;
   - holding the button for ten seconds makes the radio forget the phone.
3. **XIAO + Wio-SX1262:** it sends and receives. This checks that the RF
   switch pin (RXEN on GPIO 38) is right for your kit. It also pairs without
   digits.
4. **T-Deck:** check each of these:
   - the screen colours are right (panel inversion differs between batches);
   - the keyboard types (I2C address 0x55);
   - the trackball moves;
   - the SD card and the radio both work, since they share one SPI bus;
   - on the Plus, the GPS gets a fix (it tries 38400 baud, then 9600);
   - the PIN unlock works, and wrong PINs count towards the wipe.
5. **Range:** two radios on Balanced at 22 dBm, outdoors. Note the distance at
   which texts still arrive, then repeat on Long range.
6. **Through the radio:** a Clairix message from phone A with a radio reaches
   phone B with a radio while both have mobile data and Wi-Fi off. The relay
   copy, once data is back, is dropped as a duplicate.
7. **Lite:** pair a phone and a T-Deck over the air (the digits match on both).
   Then check:
   - texts arrive both ways;
   - an SOS arrives with a position;
   - a message sent while the other side is off is delivered when it comes
     back, through the retries.
8. **Wi-Fi Aware:** two Android 13+ phones with Bluetooth off and the mesh on
   trade a message. The Offline mesh page counts the meeting.
9. **Locked phone:** with the phone locked, a radio message is held, not lost.
   It shows after unlock.

## Not done yet

- RAK4631 (nRF52840) support: a separate radio and Bluetooth stack, deferred.
- Regions outside the US: the band plan and power rules differ, and need
  their own `region.h`.
