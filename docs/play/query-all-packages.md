# QUERY_ALL_PACKAGES: permissions declaration

Declared in `android/app/src/suite/AndroidManifest.xml`, so every suite build
(the Play edition and Clairix Full) holds it and the comms-only build does not.

## Core use (paste into the console)

Category: anti-malware / device security (an app whose core purpose is to scan
the device for harmful apps).

> Clairix is a personal security app for people who may be monitored by
> someone close to them. Its stalkerware check looks for monitoring apps on the
> phone, and those apps hide: many have no launcher icon, use an innocuous name
> and are installed by someone else with brief access to the phone. A check
> that can see only the apps it names in advance cannot find them. Clairix
> reads the list of every installed app, scores each one for the signs a
> monitoring app shows (installed from outside a store, no launcher icon, an
> enabled accessibility service, notification listener, keyboard, VPN service
> or device admin, the permissions a monitoring app needs, a foreground service
> running without ever showing a screen), and matches each against a signed
> list of known stalkerware by package name and signing certificate. The
> results are shown in Scan > Phone check > Stalkerware and stored only on the
> phone. Nothing about the installed apps is sent anywhere: the check runs
> entirely on the device, and the only network request it makes is for a newer
> signed copy of the stalkerware list, which carries no information about the
> phone's apps.

## What the code does with it

Every call that needs package visibility beyond Clairix's own `<queries>`:

| Code | What it reads | Why |
|---|---|---|
| `analysis/StalkwareScanner.kt`, `scan()` via `installedPackages()` / `installed()` (`PackageManager.getInstalledPackages`) | Every installed package with its requested permissions, services, receivers and signing certificates | Scores each app (`assess`) and matches it against the stalkerware list (`IocList.match`) |
| `analysis/StalkwareScanner.kt`, `launcherPackages()`, `vpnCapablePackages()` (`queryIntentActivities`, `queryIntentServices` with `MATCH_ALL`) | Which packages have a launcher icon; which declare a VPN service | "No launcher icon" and "can act as the VPN" are two of the signals |
| `analysis/StalkwareScanner.kt`, `activeDeviceAdminPackages()`, `owners()`, `imePackages()`, `enabledComponents()` | Active device admins, the device or profile owner, enabled keyboards, notification listeners, accessibility services | The special-access grants a monitoring app uses; each is listed with a verdict (`grantStatus`) |
| `analysis/UnknownSources.kt`, `requesters()` (`getInstalledPackages(GET_PERMISSIONS)`) | Which apps hold "Install unknown apps" | Phone check's "Install unknown apps" row: an app that can install others is how monitoring apps arrive |
| `analysis/PhoneHealthMonitor.kt` (root managers and hooking frameworks, also named in the suite manifest's `<queries>`) | Whether Magisk, KernelSU, Xposed, Frida and similar are installed | Phone check's root row |

Clairix's own package is skipped (`if (name == self)` in `scan()`), so the
check never scores itself.

## Where the data goes

- On the phone: the scan's result, in Clairix's own storage, deleted by Wipe
  Clairix or uninstalling. Privacy policy section 1: "The results of the spyware and
  phone-health checks, including the list of apps installed on your phone".
- Off the phone: nothing about the apps. `analysis/StalkwareIoc.kt` fetches a
  signed copy of the stalkerware list from GitHub about once a day while
  Clairix is used (privacy policy section 5, "Access to the installed-app list").
  The request carries no app data.

## Why a narrower query does not work

The `<queries>` element can only name packages or intents known in advance.
Stalkerware is renamed, repackaged and re-signed constantly, and the signals
that find an unknown one (no launcher icon, sideloaded, holding an
accessibility service) are properties of apps Clairix cannot name. Matching by
signing certificate also needs the app to be visible to be read at all.

## Reviewer notes

- Where to see it: Scan > Phone check > Stalkerware. On a test phone with a
  sideloaded app holding an accessibility service, the app is listed with its
  reasons.
- No account is needed for this feature.
