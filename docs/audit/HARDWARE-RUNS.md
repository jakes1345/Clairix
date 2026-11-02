# Hardware runs: SDR, scanner, ADS-B and OBD

The Drive tab's radio and vehicle features need hardware the CI emulator cannot
attach: an RTL-SDR USB dongle and a Bluetooth OBD-II adapter. This note says
plainly what has and has not been checked, so the public copy claims only what
the code and tests support.

## Status, as of this commit

**No run against real SDR, scanner, ADS-B or OBD hardware is recorded in this
repository.** There is no capture, photo, log or commit showing the
`SdrUsbDriver`, `ScannerMonitor`, `AdsbDecoder` or `ObdBluetoothManager` paths
driving a real device end to end. The emulator smoke test
(`SmokeTest.kt`) only opens the Drive sub-tabs; `.github/workflows/emulator.yml`
cannot pass a USB device or a Bluetooth OBD adapter through to the emulator.

What is covered today is the **offline decode logic**, by JVM unit tests fed
hand-built input:

| Path | Code | Unit test | What it proves without hardware |
|---|---|---|---|
| ADS-B 1090 MHz | `AdsbDecoder.kt` | `AdsbDecoderTest` (11) | CPR position decode, altitude/callsign/squawk parsing, CRC |
| FM audio | `FmDemodulator.kt` | `FmDemodulatorTest` (4) | the demodulation maths on synthetic samples |
| OBD-II | `ObdParser.kt` | `ObdParserTest` (14) | parsing ELM327 responses into engine values |
| USB match | `SdrUsbDriver.kt` | `UsbDeviceFilterTest` (2) | the dongle VID/PID match rule |

The USB transfer in `SdrUsbDriver`, the tuner bring-up, the sample stream, the
scanner's squelch and tuning in `ScannerMonitor`, the live decode in
`DriveViewModel`, and the Bluetooth RFCOMM connection in `ObdBluetoothManager`
(including its reflective `createRfcommSocket(int)` fallback, whose status under
targetSdk 35 is not verifiable from the repository) are **not** exercised by any
test.

## What the public copy now says

The feature descriptions were corrected to match the code:

- README and the in-app tour describe aircraft as **listed** (callsign, altitude,
  speed, heading), not drawn on the map — `DriveScreen.AircraftRow` renders a list;
  nothing plots aircraft on the osmdroid map.
- The tour calls the radio a **narrowband voice scanner**, not an "FM receiver".
  `FmDemodulator` has a 9 kHz channel filter and 300 µs de-emphasis and no
  wideband mode, so it demodulates NBFM voice, not broadcast (wideband) FM.

## A device run to add later

When the features are run on hardware, record the result here so the claims are
backed by evidence:

1. **Dongle.** Model, and the tuner id read back by `SdrUsbDriver.initTuner`.
2. **ADS-B.** A 1090 MHz run near an airport; a screenshot of the Aircraft list
   beside a public ADS-B reference (e.g. an online tracker) for the same aircraft.
3. **Scanner.** A run on a known local NBFM frequency; note squelch opening.
4. **OBD.** The ELM327 model, that a connection is made (direct or via the
   reflective fallback), and the PIDs read (RPM, speed, coolant).

An offline step short of real hardware would also help: capture IQ to a file and
add end-to-end tests under `src/test/resources` (an ADS-B 2 Msps capture yielding
a known ICAO through `AdsbDecoder.processChunk`; an NBFM burst opening the squelch
through `FmDemodulator`). These are not in the tree yet.
