## Option 1: In the app (immediate)

### Delete the relay account and keep the rest of the app

1. Open Clairix and tap the gear at the top right for **Settings**.
2. Under **Wipe and delete**, tap **Delete identity and number** and confirm.

The same button is at the bottom of **Comms → Settings**. Clairix asks the relay to delete your account, unregisters push, and then removes your identity keys, contacts and message history from the phone. If the relay cannot be reached, the request waits on the phone and is tried again for up to a day, as it is after a wipe (below). You can register again later, but you will get a new Clairix number and your contacts will need to add you again.

### Delete everything Clairix holds (Wipe Clairix)

1. Open **Comms**, tap the menu (⋮) at the top and choose **Wipe Clairix**. It is also in **Settings**, under **Wipe and delete**.
2. Type **WIPE** and tap **Wipe everything now**.

The wipe first destroys every key in the phone's secure keystore, then deletes every preferences file, every database and every file the app holds, including cached map tiles, and every notification. It then asks the relay to delete your account, and Clairix closes; it opens next as a new install. Once started it cannot be stopped. It erases Clairix's own data only. It does not erase your phone, and Android keeps what is its own: the app stays installed, and the system's settings still show the access you gave it. If having the app at all is the risk, uninstall it after the wipe.

The wrong-passcode limit runs the same wipe, and so does the duress passcode, except that Clairix stays on its lock screen afterwards and that code then opens it, empty.

If the relay cannot be reached at that moment, the wipe still completes on the phone, and the request to delete the account waits on it, in a file that names your Clairix number and nothing else. It is tried again about every ten minutes, whether or not Clairix is open, for up to a day. If you use Tor in Clairix, it is sent only through Tor, while the wipe runs. If it never gets through, the account stays on the relay until you ask us to delete it by email (Option 2): accounts are not deleted for being dormant.

### Uninstalling

Uninstalling Clairix removes all of its data from your phone. It does not tell the relay to delete your account. Use **Delete identity and number** or **Wipe Clairix** first, or email us.

## Option 2: By email

If you no longer have the phone, or the app will not open, write to **support@clairix.app** with the subject line **Delete my account** and include:

- Your **Clairix number** (nine digits). This is the only identifier we hold, and without it we cannot find your account. It is shown in Comms settings and to the contacts you paired with; if you have lost it, ask one of them.
- Optionally, the approximate date you registered.

We will delete the account and confirm by reply within 30 days, usually much sooner. We will not ask you for your name or any other personal information, because we do not hold any to match it against. How we do it is public: [the relay operator's runbook](https://github.com/jakes1345/Clairix/blob/main/comms-worker/README.md#deleting-an-account-on-request).

Because the relay has no email address or phone number on file, we cannot verify that a request comes from the account's owner. We treat a deletion request that names a valid Clairix number as authoritative. If someone deletes your account without your consent, the harm is limited to needing to re-pair with your contacts, since no message content is held on the relay.

## What is deleted

<div class="stack deletion">

| Data | In the app: Delete identity and number, or Wipe Clairix | Email request |
|---|---|---|
| Clairix number and public keys | Deleted when the relay is reached | Deleted on processing |
| Encrypted envelopes waiting for you | Deleted when the relay is reached | Deleted on processing |
| Push endpoint | Deleted when the relay is reached | Deleted on processing |
| Unused invite codes you created | Expire within 7 days | Expire within 7 days |
| Keys, contacts and message history on your phone | Deleted at once | Not affected; uninstall the app |
| Scan results, timeline, location history, unlock ledger, reports on your phone | Wipe Clairix deletes; Delete identity and number keeps | Not affected; uninstall the app |
| Clairix Full only: coin balance and transfer history | Deleted when the relay is reached | Deleted on processing |

</div>

## What is not deleted, and why

- **Messages already delivered to other people.** They hold their own copies on their own phones, encrypted with their own keys. We cannot reach them and neither can the relay.
- **Anonymous traffic samples** sent while navigating. These carry no account at all, only a pseudonym your phone made up for that drive and replaced every ten minutes, and they are deleted automatically within two hours.
- **Clairix Full only: other people's record of coin transfers** with you. Each account keeps its own transaction list.

## Retention if you do nothing

Relay data also expires on its own:

- Envelopes that are never collected are deleted after 30 days.
- Invite codes expire after 7 days.
- Traffic samples are deleted after 2 hours.
- Your Clairix number and public keys remain until the account is deleted. Dormant accounts are not deleted automatically at present; if that changes, this page will say so.

## Questions

Write to support@clairix.app. For the full picture of what Clairix stores and where, read the [Privacy Policy](/privacy) and the [Security page](/security).
