package app.clairix.analysis

import android.provider.Settings
import app.clairix.HealthFact
import app.clairix.PhoneHealth
import app.clairix.PhoneHealthFinding
import app.clairix.Severity
import app.clairix.analysis.StalkwareScanner.GrantKind
import app.clairix.analysis.StalkwareScanner.GrantStatus

/**
 * What the stalkerware check and the grant verdicts ([StalkwareScanner.grantStatus]) look like in
 * Phone check: a finding for each app the check scores HIGH, a finding for each grant that needs
 * a look, and an "Access granted" row, with no severity, for each grant the scanner vouches for or
 * the owner marked as theirs. Kept apart from Android so it is tested without a phone.
 */
internal object SpywareHealth {

    /** Finding ids: `spy:<package>` for an app, `grant:<kind>:<package>[/<component>]` for a grant; row ids `granted:…`. */
    const val APP_PREFIX = "spy:"
    const val GRANT_PREFIX = "grant:"
    const val GRANTED_PREFIX = "granted:"

    fun grantId(prefix: String, kind: GrantKind, packageName: String, component: String? = null): String =
        "$prefix${kind.key}:$packageName" + (component?.let { "/$it" } ?: "")

    /** The package a finding or row id is about, or null for an id of some other kind. */
    fun packageOf(id: String): String? = when {
        id.startsWith(APP_PREFIX) -> id.removePrefix(APP_PREFIX)
        id.startsWith(GRANT_PREFIX) || id.startsWith(GRANTED_PREFIX) ->
            id.substringAfter(':').substringAfter(':').substringBefore('/').takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * One finding for each app [result] scores HIGH: CRITICAL for one on the stalkerware list, as
     * a match by name or certificate is as sure as this check gets, HIGH for the rest.
     */
    fun appFindings(result: StalkwareScanner.Result?): List<PhoneHealthFinding> = result?.high.orEmpty().map { app ->
        val known = app.knownAs
        PhoneHealthFinding(
            id = APP_PREFIX + app.packageName,
            severity = if (known != null) Severity.CRITICAL else Severity.HIGH,
            category = "Stalkerware",
            title = when {
                known == null -> "${app.appLabel} looks like stalkerware"
                known.equals(app.appLabel, ignoreCase = true) -> "${app.appLabel} is known stalkerware"
                else -> "${app.appLabel} is known stalkerware: $known"
            },
            detail = "Why: ${app.reasons.joinToString("; ")}. Stalkerware sends what happens on a phone (messages, calls, " +
                "location, often the microphone) to whoever installed it. Phone check → Stalkerware shows what it holds and " +
                "where each thing is switched off."
        )
    }

    /** The finding for a grant that needs a look: [severity] is [StalkwareScanner.grantSeverity] of [status]. */
    fun grantFinding(
        kind: GrantKind,
        label: String,
        packageName: String,
        component: String?,
        status: GrantStatus,
        severity: Severity,
    ): PhoneHealthFinding {
        val mark = "If it is yours, mark it as yours in Phone check → Stalkerware."
        val (category, title, detail) = when (kind) {
            GrantKind.ACCESSIBILITY -> Triple(
                "Accessibility", "$label — accessibility service active",
                "Accessibility services can read every word on screen, intercept key presses, perform taps on your behalf, " +
                    "and run in the background indefinitely. Stalkerware almost always registers as one, and Clairix does not " +
                    "recognise this one. If you did not turn it on yourself, switch it off under Settings → Accessibility → " +
                    "Installed apps. $mark"
            )
            GrantKind.NOTIFICATION_LISTENER -> Triple(
                "Notification access", "$label — notification access",
                "This app reads every notification as it arrives, one-time codes and message previews included, and Clairix " +
                    "does not recognise it. If you did not give it access yourself, switch it off under Settings → Notifications → " +
                    "Notification access (on some phones Device & app notifications). $mark"
            )
            GrantKind.DEVICE_ADMIN -> if (status == GrantStatus.CONFIRM) Triple(
                "Device Admin", "$label — work or school management",
                "A device-management app holds an active device admin. On a work or school phone that is expected, but anyone can " +
                    "install one from a store and enrol a phone in it in minutes. If you did not enrol this phone yourself, someone " +
                    "else can see and control parts of it. If you did, confirm it in Phone check → Stalkerware. It is listed under " +
                    "Settings → Security → Device admin apps."
            ) else Triple(
                "Device Admin", "$label — device admin",
                "Device admin apps can lock or wipe the device, enforce password policies, and block uninstallation. MDM agents " +
                    "and stalkerware both use this, and Clairix does not recognise this one. Revoke it from Settings → Security → " +
                    "Device admin apps. $mark"
            )
            GrantKind.KEYBOARD -> Triple(
                "Keyboard", "$label — keyboard Clairix does not know",
                "An enabled keyboard sees everything typed while it is in use, passwords and messages included. If you did not " +
                    "install it yourself, switch it off under Settings → System → Keyboard, then uninstall it. $mark"
            )
            GrantKind.DEVICE_OWNER, GrantKind.PROFILE_OWNER -> Triple(
                if (kind == GrantKind.DEVICE_OWNER) "Device owner" else "Profile owner",
                "This phone is managed by $label",
                (if (kind == GrantKind.DEVICE_OWNER) "$label is this phone's device owner. "
                else "$label owns the profile Clairix runs in, as Family Link supervision and some work set-ups do. ") +
                    "An owner can install and remove apps without asking, hide apps, set location and other policies, and install " +
                    "certificates that let it read encrypted traffic, and Settings does not let you switch it off. Did you enrol this " +
                    "phone yourself (a work phone, or Family Link you set up)? If you did, confirm it in Phone check → Stalkerware. " +
                    "If you did not, someone else enrolled it."
            )
        }
        return PhoneHealthFinding(grantId(GRANT_PREFIX, kind, packageName, component), severity, category, title, detail)
    }

    /** The "Access granted" row for a grant the scanner vouches for ([GrantStatus.KNOWN]) or the owner marked ([GrantStatus.YOURS]). */
    fun grantedFact(kind: GrantKind, label: String, packageName: String, component: String?, status: GrantStatus): HealthFact {
        val yours = status == GrantStatus.YOURS
        val owner = kind == GrantKind.DEVICE_OWNER || kind == GrantKind.PROFILE_OWNER
        val value = when {
            owner -> "Manages this phone · you enrolled it"
            yours -> "Access granted · marked as yours"
            else -> "Access granted · known app"
        }
        val undo = "If you did not mark it, undo it in Phone check → Stalkerware."
        val detail = when (kind) {
            GrantKind.ACCESSIBILITY -> if (yours) "You marked this accessibility app as yours. It can read the screen and act for you. $undo"
            else "A known accessibility app from a store or the phone's own system. It can read the screen and act for you, which is what it is for. Settings → Accessibility switches it off."
            GrantKind.NOTIFICATION_LISTENER -> if (yours) "You marked this app's notification access as yours. It reads every notification. $undo"
            else "A known app from a store or the phone's own system. It reads every notification, which is what it needs the access for."
            GrantKind.DEVICE_ADMIN -> if (yours) "You confirmed this device admin is yours. It can block its own uninstall and may lock or wipe the phone. $undo"
            else "A device admin with a stated purpose (finding a lost phone, a mail account's security rules), from a store or the phone's own system. Settings → Security → Device admin apps lists it."
            GrantKind.KEYBOARD -> if (yours) "You marked this keyboard as yours. It sees everything typed with it. $undo"
            else "A known keyboard from a store or the phone's own system."
            GrantKind.DEVICE_OWNER, GrantKind.PROFILE_OWNER ->
                "You confirmed you enrolled this phone with $label. It can install and remove apps, hide apps, set policies and " +
                    "install certificates. If you did not confirm it, undo it in Phone check → Stalkerware."
        }
        return HealthFact(
            id = grantId(GRANTED_PREFIX, kind, packageName, component),
            category = "Apps",
            label = "$label (${kind.label})",
            value = value,
            detail = detail,
            fixAction = settingsFor(kind),
        )
    }

    /** The Settings page each grant is switched off on. */
    fun settingsFor(kind: GrantKind): String = when (kind) {
        GrantKind.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        GrantKind.NOTIFICATION_LISTENER -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        GrantKind.KEYBOARD -> Settings.ACTION_INPUT_METHOD_SETTINGS
        GrantKind.DEVICE_ADMIN, GrantKind.DEVICE_OWNER, GrantKind.PROFILE_OWNER -> Settings.ACTION_SECURITY_SETTINGS
    }

    /**
     * [health] with the app findings replaced by [apps] and the check's time set: a grant finding
     * about an app that is itself among [apps] is dropped, since the app's finding lists the grant
     * among its reasons and one app should not count as two issues. Worst first.
     */
    fun merge(health: PhoneHealth, apps: List<PhoneHealthFinding>, scannedAt: Long): PhoneHealth {
        val flagged = apps.mapNotNullTo(HashSet()) { packageOf(it.id) }
        val kept = health.findings.filterNot { f ->
            f.id.startsWith(APP_PREFIX) || (f.id.startsWith(GRANT_PREFIX) && packageOf(f.id) in flagged)
        }
        val findings = (apps + kept).distinctBy { it.id }.sortedByDescending { it.severity.ordinal }
        return health.copy(findings = findings, spywareScannedTs = scannedAt)
    }

    /**
     * The checklist row on what the check cannot see: apps in any profile but the one Clairix runs
     * in. [others] is how many other profiles Android shows Clairix; [secureFolder] whether
     * Samsung's Secure Folder is installed; [sdk] the Android version, for Private space (15+),
     * which Android hides from every app but the launcher; [ownProfile] the profile Clairix itself
     * is installed in ([StalkwareScanner.ownProfile]), when it is not the main one: then none of
     * the phone's main apps are checked, which is worth a look (MEDIUM), not information.
     */
    fun profilesFact(others: Int, secureFolder: Boolean, sdk: Int, ownProfile: String? = null): HealthFact {
        val detail = buildString {
            if (ownProfile != null) {
                append(
                    "Clairix is installed in $ownProfile, so it checks the apps there and none of the phone's main ones. " +
                        "To check those, install Clairix in the main profile too. "
                )
            }
            append(
                "Clairix checks the apps in the profile it runs in and no other: apps in a work profile, a dual-apps or clone " +
                    "profile, Samsung's Secure Folder, a second space or Android's Private space are not scanned."
            )
            if (others > 0) {
                append(
                    " This phone has $others other ${if (others == 1) "profile" else "profiles"} that Android shows apps; look " +
                        "through the apps in each yourself (Settings → Apps shows a tab for each profile)."
                )
            }
            if (secureFolder) {
                append(
                    " Secure Folder is installed; if it has been set up, it opens from Settings → Security and privacy → More " +
                        "security settings → Secure Folder."
                )
            }
            if (sdk >= 35) {
                append(
                    " Android keeps Private space hidden from apps, so it is never counted here: if you did not set one up, look " +
                        "under Settings → Security & privacy → Private space."
                )
            }
        }
        return HealthFact(
            id = "profiles", category = "Apps", label = "Other profiles",
            value = when {
                ownProfile != null -> "Clairix runs in $ownProfile; the main profile is not checked"
                others == 0 -> "None Clairix can see"
                else -> "$others Clairix can see, not scanned"
            },
            severity = if (ownProfile != null) Severity.MEDIUM else null,
            detail = detail,
        )
    }
}
