## Option 1: In the app (immediate)

### Delete the relay account and keep the rest of the app

1. Open Clairix and go to **Comms**.
2. Open **Settings** in Comms.
3. Tap **Unpair** and confirm.

Clairix asks the relay to delete your account, unregisters push, and then removes your identity keys, contacts and message history from the phone. You can register again later, but you will get a new Clairix number and your contacts will need to re-pair with you.

### Delete everything Clairix holds (Panic Wipe)

1. Open Clairix and go to **Settings**.
2. Tap **Panic Wipe** and confirm.

Panic Wipe first asks the relay to delete your account, then deletes every key in the phone's secure keystore, every preferences file, every database and every file the app holds, including cached map tiles, and restarts the app to a blank slate. Once started it cannot be stopped. It erases Clairix's own data only. It does not erase your phone.

If the relay cannot be reached at that moment, the wipe still completes on the phone. The relay account will then expire on its own as described below, or you can ask us to delete it by email.

### Uninstalling

Uninstalling Clairix removes all of its data from your phone. It does not tell the relay to delete your account. Use Unpair or Panic Wipe first, or email us.

## Option 2: By email

If you no longer have the phone, or the app will not open, write to **support@clairix.app** with the subject line **Delete my account** and include:

- Your **Clairix number** (nine digits). This is the only identifier we hold, and without it we cannot find your account. It is shown in Comms settings and to the contacts you paired with; if you have lost it, ask one of them.
- Optionally, the approximate date you registered.

We will delete the account and confirm by reply within 30 days, usually much sooner. We will not ask you for your name or any other personal information, because we do not hold any to match it against.

Because the relay has no email address or phone number on file, we cannot verify that a request comes from the account's owner. We treat a deletion request that names a valid Clairix number as authoritative. If someone deletes your account without your consent, the harm is limited to needing to re-pair with your contacts, since no message content is held on the relay.

## What is deleted

<div class="stack deletion">

| Data | In-app Unpair / Panic Wipe | Email request |
|---|---|---|
| Clairix number and public keys | Deleted at once | Deleted on processing |
| Encrypted envelopes waiting for you | Deleted at once | Deleted on processing |
| Push endpoint | Deleted at once | Deleted on processing |
| Unused invite codes you created | Expire within 7 days | Expire within 7 days |
| Keys, contacts and message history on your phone | Deleted at once | Not affected; uninstall the app |
| Scan results, timeline, location history, unlock ledger, reports on your phone | Panic Wipe deletes; Unpair keeps | Not affected; uninstall the app |
| Clairix Full only: coin balance and transfer history | Deleted at once | Deleted on processing |

</div>

## What is not deleted, and why

- **Messages already delivered to other people.** They hold their own copies on their own phones, encrypted with their own keys. We cannot reach them and neither can the relay.
- **Anonymous traffic samples** sent while navigating. These carry only a salted one-way hash of your number and are deleted automatically within two hours.
- **Clairix Full only: other people's record of coin transfers** with you. Each account keeps its own transaction list.

## Retention if you do nothing

Relay data also expires on its own:

- Envelopes that are never collected are deleted after 30 days.
- Invite codes expire after 7 days.
- Traffic samples are deleted after 2 hours.
- Your Clairix number and public keys remain until the account is deleted. Dormant accounts are not deleted automatically at present; if that changes, this page will say so.

## Questions

Write to support@clairix.app. For the full picture of what Clairix stores and where, read the [Privacy Policy](/privacy) and the [Security page](/security).
