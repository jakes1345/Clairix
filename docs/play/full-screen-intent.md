# USE_FULL_SCREEN_INTENT: full-screen intent declaration

Declared in `android/app/src/main/AndroidManifest.xml`, so every build holds
it. From Android 14, Google Play grants full-screen intents by default only to
apps whose core function is calling or alarms; Clairix declares itself a
calling app.

## Core use (paste into the console)

Use case: calling (incoming voice calls).

> Clairix includes end-to-end encrypted voice calling (Clairix Comms). When a
> call comes in, Clairix shows a full-screen incoming-call screen over the lock
> screen, with Answer and Decline, as the phone's own dialer does; without it a
> call on a locked phone would show only as a notification and would usually be
> missed. The full-screen intent is used for incoming calls only: the encrypted
> call screen, and Clairix's fake incoming call, a safety feature the user
> schedules as a reason to leave a situation, which has to look and ring like a
> real call to work. Neither is shown for any other notification.

## What the code does

Every `setFullScreenIntent` in the app, and nothing else:

| Code | Notification | When |
|---|---|---|
| `comms/CommsNotifications.kt`, `incomingCall` (`CallStyle.forIncomingCall`, `CATEGORY_CALL`) | Opens `CallActivity` (shows over the lock screen) | An encrypted call is ringing |
| `comms/CommsNotifications.kt`, `lockedCall` | Opens `LockedRingActivity` | A call is ringing on a phone whose offer cannot be opened until it is unlocked; names no caller |
| `security/FakeCall.kt`, `card` | Opens `security.FakeCallActivity` | The fake call the user scheduled (Comms menu > Fake call) rings |

`CommsNotifications.canUseFullScreen` reads
`NotificationManager.canUseFullScreenIntent()` on Android 14+; when the user (or
the phone) has turned the access off, calls ring as a heads-up notification
only, and the Fake call page says so and offers **Allow**, which opens Android's
setting (`CommsNotifications.openFullScreenSettings`).

## Reviewer notes

- Incoming call: two test phones registered with Comms and paired; lock one and
  call it from the other. The full-screen call screen appears.
- Fake call: Comms menu > Fake call, schedule one **In 30 s**, lock the phone.
  The fake call screen appears over the lock screen and rings.
