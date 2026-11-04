package app.clairix.analysis

/**
 * The F-Droid edition: built without Google Play services or Firebase (CI checks its
 * dependency graph), so there is no fused client to offer. With [fused] null, [Positioning.platform] reports
 * no Play services and [LocationPolicy.choose] never picks [LocationSource.GOOGLE]: every
 * request goes to Android's own location service, through `android.location.LocationManager`
 * (its fused provider where the phone has one, otherwise its satellite and Wi-Fi/cell
 * providers; the satellite receiver alone with Satellite-only positioning on), exactly
 * the path the other editions take on a phone without Play services. SOS lookups do the
 * same.
 */
object EditionLocation {
    val fused: FusedLocation? = null
}
