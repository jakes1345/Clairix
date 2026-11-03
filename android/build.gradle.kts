plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Paparazzi 1.3.x copies android.os.Build's fields from its own layoutlib
    // and fails on the API 36 android.jar (a field it does not have);
    // 2.0.0-alpha02 is the first with an API 36 layoutlib and the last that
    // runs on Java 17 (alpha04 and later need 21).
    id("app.cash.paparazzi") version "2.0.0-alpha02" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.cyclonedx.bom") version "3.5.1" apply false
    // The Android libraries' licences for Settings > About > Third-party
    // licences (app/build.gradle.kts, thirdPartyNotices). 15.0 and later need
    // the 8.13 plugin this build now has; moving to them is its own change,
    // checked against the notices it writes.
    id("com.mikepenz.aboutlibraries.plugin") version "14.2.1" apply false
}
