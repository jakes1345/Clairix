# Foreground services: permissions declaration and videos

Every foreground service the Play build declares, by type, with the console
text and a demo video script for each type. The test
`android/app/src/test/java/app/clairix/comms/ForegroundServiceManifestTest.kt`
checks the manifests: the relay connection's type and stated reason, and that
every declared type has its `FOREGROUND_SERVICE_*` permission in the same APK.

| Type | Permission | Service | Manifest | Started by |
|---|---|---|---|---|
| `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` | `comms.CommsService` | `src/main` | Comms settings, **Online** (and at boot or after an update, if it was on) |
| `microphone` | `FOREGROUND_SERVICE_MICROPHONE` | `comms.CallService` | `src/main` | Placing a call (**Call** in a conversation) or answering one on the call screen |
| `location` | `FOREGROUND_SERVICE_LOCATION` | `ScanService` | `src/suite` | Scan > Trackers, **Start scanning** (and `BootReceiver`; see [background-location.md](background-location.md)) |
| `location` | `FOREGROUND_SERVICE_LOCATION` | `nav.NavigationService` | `src/suite` | Drive > navigation, **Go** on a route |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | `comms.mesh.MeshService` | `src/suite` | Comms menu > Offline mesh, **Turn on** |
| `connectedDevice` (plus `location` when location is granted) | both of the above | `DriveService` | `src/suite` | Drive, **Turn on drive mode** |
| `shortService` | none | `security.FakeCallRingService` | `src/main` | The fake call's alarm; rings for at most 45 seconds |

`shortService` needs no type permission and no declaration; it is listed so
the table is complete.

## specialUse: the encrypted relay connection

Subtype, as declared in the manifest (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` on
`CommsService`):

> Keeps the end-to-end-encrypted relay connection open so incoming calls and
> messages arrive without a Google push service

Console text:

> Clairix Comms is an end-to-end encrypted messenger and calling app that does
> not use Firebase Cloud Messaging or any Google push service: the people it is
> built for (people at risk from someone who may have access to their Google
> account) need messages and calls that do not pass through one. While the user
> keeps Comms "Online" (Comms settings), this service holds one WebSocket open
> to the user's relay server, so a sealed message or an incoming call offer
> arrives the moment it is sent and the phone can ring with the app closed. No
> named foreground-service type fits: remoteMessaging is for moving a user's
> messages between their own devices, and dataSync is for transfers that end.
> The service shows an ongoing notification, stops when the user turns Online
> off or taps "Go offline" on the notification, and does no other work. Users who prefer
> can turn Online off and receive wake-ups through a UnifiedPush distributor of
> their choice instead.

What the code does: `comms/CommsService.kt` goes foreground with
`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on Android 14+ (untyped
before), takes a hold on `LiveLink`'s socket and mirrors its state in the
notification (`CommsNotifications.linkNotification`). The socket, its
heartbeat and reconnection live in `LiveLink`; the service owns no other work.

Video (30 seconds):

| Time | On screen | Caption |
|---|---|---|
| 0:00-0:06 | Comms tab, **Settings**, the **Online** switch turned on. The ongoing "connected" notification appears. | [The user keeps the encrypted connection open] |
| 0:06-0:10 | Press Home; swipe Clairix out of recents. Pull down the shade: the notification stays. | [App closed, connection held by the service] |
| 0:10-0:22 | A second phone places a Clairix call to this one. This phone rings: the incoming-call screen over the lock screen. Answer, then hang up. | [An incoming call rings with the app closed, no Google push] |
| 0:22-0:30 | Comms settings again, **Online** off: the notification disappears. | [The user can stop it at any time] |

## microphone: a call in progress

Console text:

> During an encrypted voice call the user places or answers in Clairix, this
> service holds the microphone and the WebRTC connection so the call continues
> with the screen off or another app on top. It starts only from a Clairix
> screen (placing a call, or answering on the call screen) and stops when the
> call ends. Its notification is the ongoing-call card with a hang-up button.

What the code does: `comms/CallService.kt`, `startForeground(...,
FOREGROUND_SERVICE_TYPE_MICROPHONE)`; started from `CallManager` when a call is
placed or answered.

Video (20 seconds):

| Time | On screen | Caption |
|---|---|---|
| 0:00-0:06 | A conversation; tap **Call**. The call screen shows ringing, then connected. | [User places an encrypted call] |
| 0:06-0:14 | Press Home; the ongoing-call notification stays; the other phone still hears this one. | [The call continues in the background] |
| 0:14-0:20 | Tap hang up on the notification; it disappears. | [Service ends with the call] |

## location: the tracker scanner and navigation

Console text:

> Two user-started features need location while the screen is off. The tracker
> scanner (Scan > Trackers > Start scanning) compares where each Bluetooth
> device is heard with where the phone is, to tell whether a tracker is moving
> with the user; it runs until the user stops it, with an ongoing notification.
> Turn-by-turn navigation (Drive, Go on a route) needs a position every second
> to give directions with the screen off; it runs only while navigating.
> Location history stays on the phone.

What the code does: `ScanService` (`FOREGROUND_SERVICE_TYPE_LOCATION`) runs the
scanner; `nav/NavigationService.kt` holds the foreground state while
`nav/Navigator.kt` navigates. `DriveService` adds the location type to its
connected-device type when location is granted (`DriveService.enterForeground`);
drive mode's own work (the radio stick, the OBD-II adapter) reads no position,
so the drive-mode video below shows connectedDevice only.

Video (30 seconds):

| Time | On screen | Caption |
|---|---|---|
| 0:00-0:06 | Scan > Trackers, **Start scanning**; the scanner notification appears. | [User starts the tracker scanner] |
| 0:06-0:14 | Lock the phone, walk; unlock: Scan > Trackers lists nearby devices with how far each has moved with you. | [Location shows whether a device moves with you] |
| 0:14-0:24 | Drive, pick a destination, **Go**. Turn the screen off and on: the next turn is spoken and shown. | [Navigation keeps directions coming with the screen off] |
| 0:24-0:30 | End navigation, then stop the scanner in Scan > Trackers: both notifications go. | [Both stop when the user stops them] |

## connectedDevice: the offline mesh and drive mode

Console text:

> Two user-started features keep a link to a nearby device open with the
> screen off. The offline mesh (Comms menu > Offline mesh > Turn on) passes
> sealed messages and SOS alerts over Bluetooth to nearby phones running
> Clairix when the network is down; it runs until the user turns it off. Drive
> mode (Drive > Turn on drive mode) keeps a USB radio receiver (RTL-SDR) and a
> Bluetooth OBD-II car adapter connected and streaming while driving; it runs
> until the user turns it off, and stops on a low battery.

What the code does: `comms/mesh/MeshService.kt`
(`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`) runs `Mesh.startNode`;
`DriveService` holds the foreground state while `DriveViewModel` runs the
radio and the adapter.

Video (30 seconds):

| Time | On screen | Caption |
|---|---|---|
| 0:00-0:10 | Comms menu, **Offline mesh**, **Turn on**; the mesh notification appears. Two phones with the mesh on and Wi-Fi and data off: a text sent on one arrives on the other. | [Messages pass phone to phone over Bluetooth] |
| 0:10-0:14 | **Turn off**: the notification goes. | [Stops when the user turns it off] |
| 0:14-0:26 | Drive with an RTL-SDR stick plugged in, **Turn on drive mode**; the drive-mode notification; screen off and on, the scanner still receiving. | [Drive mode keeps the USB receiver streaming] |
| 0:26-0:30 | **Turn off drive mode**: the notification goes. | [Stops when the user turns it off] |
