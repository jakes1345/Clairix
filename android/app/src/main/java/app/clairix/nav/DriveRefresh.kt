package app.clairix.nav

/**
 * What the Drive screen's minute loop refreshes, from what the screen is doing.
 *
 * Not navigating, everything it fetches is for the map in front of the owner: live
 * traffic, and for a previewed route the HERE/TomTom incidents and the work zones. So
 * it fetches only while the Drive screen is in front ([resumed]); in the background
 * the loop sleeps until the screen comes back or a drive starts ([awake]), instead of
 * waking every minute to fetch for nobody. Navigating, the navigator keeps traffic
 * current itself and this keeps the work zones fresh, in front or not: the drive goes
 * on with the screen off.
 */
internal object DriveRefresh {

    data class Plan(val live: Boolean, val providers: Boolean, val workZones: Boolean)

    /** Whether the loop has anything to do; while not, it waits without waking. */
    fun awake(navigating: Boolean, resumed: Boolean): Boolean = navigating || resumed

    fun plan(hasSession: Boolean, navigating: Boolean, previewing: Boolean, resumed: Boolean): Plan = Plan(
        live = hasSession && !navigating && resumed,
        providers = hasSession && previewing && !navigating && resumed,
        workZones = hasSession && (navigating || (previewing && resumed))
    )
}
