package app.clairix.security

/**
 * The wipe limit [AppLock] applies: the [stored] one once the owner chose it
 * herself, otherwise none (0, the lockout). Every version before the choice
 * existed wrote a limit of 3 with each new passcode, without asking; such a
 * limit is not applied until she confirms it.
 */
internal fun appliedWipeLimit(stored: Int, chosen: Boolean): Int = if (chosen) stored else 0

/**
 * The limit [appliedWipeLimit] holds back on a phone with a passcode: the
 * [stored] one nobody chose, to ask the owner about; 0 when there is none.
 */
internal fun heldBackWipeLimit(stored: Int, chosen: Boolean, hasPasscode: Boolean): Int =
    if (hasPasscode && !chosen && stored > 0) stored else 0
