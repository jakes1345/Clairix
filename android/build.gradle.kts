plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("app.cash.paparazzi") version "1.3.5" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.cyclonedx.bom") version "3.5.1" apply false
    // The Android libraries' licences for Settings > About > Third-party
    // licences (app/build.gradle.kts, thirdPartyNotices). 14.2.1 (May 2026) is
    // the newest release that supports this Android Gradle plugin: 15.0 and
    // later refuse any older than 8.13.
    id("com.mikepenz.aboutlibraries.plugin") version "14.2.1" apply false
}
