# Google Play declarations

What the Play edition (`playSuite`, `clairix-play-<tag>.aab`) declares that
Google Play reviews, and the text and demo videos prepared for each review.
The Play listing is not public yet (`PLAY_LIVE = false` in
`website/src/config/site.ts`); these files exist so that the first review is
not written in a console form under time pressure, and so that every answer can
be checked against the code and the privacy policy it has to match.

Each file quotes the code it relies on by path and function name. When the code
changes, the file changes in the same commit; the Play Console checklist in
[android/README.md](../../android/README.md) ("Cutting a release", step 5) says
when each file is pasted into the console.

| What the Play build declares | Where | Console form | File |
|---|---|---|---|
| `QUERY_ALL_PACKAGES` | `src/suite/AndroidManifest.xml` | Permissions declaration: All files and packages / package visibility | [query-all-packages.md](query-all-packages.md) |
| `ACCESS_BACKGROUND_LOCATION` | `src/suite/AndroidManifest.xml` | Location permissions declaration, with a video | [background-location.md](background-location.md) |
| Foreground services: `specialUse`, `microphone`, `location`, `connectedDevice` | `src/main` and `src/suite` manifests | Foreground service permissions declaration, one video per type | [foreground-services.md](foreground-services.md) |
| `USE_FULL_SCREEN_INTENT` | `src/main/AndroidManifest.xml` | Full-screen intent declaration | [full-screen-intent.md](full-screen-intent.md) |
| What leaves the phone | `website/src/content/docs/privacy.md` section 2 | Data safety | [data-safety.md](data-safety.md) |

Also declared, with no console form of their own, but read by review and
described in the store listing:

- `PACKAGE_USAGE_STATS` (usage access, a special access the owner grants in
  Android settings): the unlock ledger reads the platform's own record of
  unlocks, and the stalkerware check reads which apps ran a foreground service
  without ever showing a screen (`analysis/StalkwareScanner.kt`,
  `usageSignals`). Nothing is read without the grant, and nothing read leaves
  the phone.
- The device admin (`security/UnlockAdminReceiver`, `res/xml/unlock_admin.xml`):
  watch-login, so the platform reports each unlock success and failure to the
  unlock ledger, and force-lock, for the Fake call page's "Schedule and lock".
  The owner switches it on with "Enable device admin" on the Unlock ledger
  page, or "Enable" on the Fake call page; it asks for no wipe or password
  policy.
- `SCHEDULE_EXACT_ALARM` (the fake call rings on the second): a special access
  the owner grants. Clairix does not declare `USE_EXACT_ALARM`, which is the one
  Play restricts to alarm and calendar apps.

Google's rules quoted in these files are as Google published them when they
were written. The console's own wording on the day of submission wins; if it
asks for something these files do not cover, add it here first.
