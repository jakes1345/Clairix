import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.TreeMap
import java.util.TreeSet

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // JVM screenshot tests of the Compose screens (no device): layoutlib renders
    // them; recordPaparazziFullSuiteDebug writes the PNGs to src/test/snapshots
    // and verifyPaparazziFullSuiteDebug compares against them (android.yml).
    id("app.cash.paparazzi")
    // Kotlin static analysis; see the detekt block below.
    id("io.gitlab.arturbosch.detekt")
    // The bill of materials attached to each release; see cyclonedxDirectBom below.
    id("org.cyclonedx.bom")
    // The licences the app's Android libraries publish, for the third-party
    // notices the app shows (thirdPartyNotices, below).
    id("com.mikepenz.aboutlibraries.plugin")
}

// ── COMMS end-to-end encryption: the Rust crate in ../comms-crypto ─────────
//
// vodozemac (Olm double ratchet) plus the sealed-envelope layer live in Rust and
// are exposed to Kotlin through UniFFI. Two tasks make `gradle assembleDebug`
// self-contained: cargo-ndk cross-compiles the crate into jniLibs, and
// uniffi-bindgen generates the Kotlin binding from the compiled library. Both
// need Rust, cargo-ndk and an NDK on the machine (see .github/workflows/android.yml).

val rustCrate = rootProject.file("../comms-crypto")
val rustJniLibs = layout.buildDirectory.dir("rust/jniLibs")
val rustBindings = layout.buildDirectory.dir("rust/kotlin")
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * The NDK and cargo-ndk every build uses. A release can only be rebuilt
 * byte for byte with the same tools, so both are pinned here and the build
 * stops on any other version instead of quietly producing different native
 * libraries. Install the NDK with
 *   sdkmanager "ndk;27.2.12479018"
 * and cargo-ndk with
 *   cargo install cargo-ndk --version 4.1.2 --locked
 * The CI workflows install exactly these. Bump all three places together.
 */
val pinnedNdkVersion = "27.2.12479018"
val pinnedCargoNdkVersion = "4.1.2"

fun ndkRevision(dir: File): String? {
    val props = Properties()
    dir.resolve("source.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) } ?: return null
    return props.getProperty("Pkg.Revision")?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The pinned NDK: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) if it names it, else
 * ndk/<pinned> under the SDK. cargo-ndk fails with "Error detecting NDK
 * version for path" when it is handed nothing, which is what an unset
 * variable looks like in CI.
 */
fun resolveNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")
        .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory && ndkRevision(it) == pinnedNdkVersion }
        ?.let { return it }
    val sdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { propsFile ->
            val props = Properties()
            propsFile.inputStream().use { stream -> props.load(stream) }
            props.getProperty("sdk.dir")?.let { dir -> File(dir) }
        }
    return sdk?.resolve("ndk/$pinnedNdkVersion")?.takeIf { ndkRevision(it) == pinnedNdkVersion }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles comms-crypto for every Android ABI with cargo-ndk"
    workingDir = rustCrate
    inputs.dir(rustCrate.resolve("src"))
    inputs.files(rustCrate.resolve("Cargo.toml"), rustCrate.resolve("Cargo.lock"))
    outputs.dir(rustJniLibs)
    doFirst {
        val ndk = resolveNdkDir()
            ?: throw GradleException("Android NDK $pinnedNdkVersion not found: run sdkmanager \"ndk;$pinnedNdkVersion\" (or point ANDROID_NDK_HOME at it)")
        val cargoNdk = runCatching {
            ProcessBuilder("cargo", "ndk", "--version").redirectErrorStream(true).start()
                .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        }.getOrDefault("")
        if (!Regex("""\bcargo-ndk $pinnedCargoNdkVersion\b""").containsMatchIn(cargoNdk)) {
            throw GradleException("cargo-ndk $pinnedCargoNdkVersion is required, found \"${cargoNdk.trim()}\": run cargo install cargo-ndk --version $pinnedCargoNdkVersion --locked")
        }
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        logger.lifecycle("cargo-ndk $pinnedCargoNdkVersion using NDK $pinnedNdkVersion at ${ndk.absolutePath}")
        // rustc writes the absolute paths of the crate and of Cargo's registry
        // into the library (panic locations, debug info), so a build from any
        // other directory would differ for no reason. Mapped to fixed names;
        // .github/workflows/android.yml checks that none is left. cargo-ndk
        // passes its own flags through a linker wrapper, not RUSTFLAGS, so
        // these are not trampled.
        fun fixed(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val cargoHome = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".cargo")
        val remap = "--remap-path-prefix=${fixed(rustCrate)}=/comms-crypto " +
            "--remap-path-prefix=${fixed(cargoHome)}=/cargo"
        val flags = System.getenv("RUSTFLAGS")?.takeIf { it.isNotBlank() }?.let { "$it $remap" } ?: remap
        environment("RUSTFLAGS", flags)
        // The same for C and assembly that crates build with the cc crate
        // (ring's, under Tor's TLS): clang writes source paths into debug info.
        // cargo-ndk sets CFLAGS_<target> for its own flags; cc adds CFLAGS too.
        val prefixMap = "-ffile-prefix-map=${fixed(rustCrate)}=/comms-crypto " +
            "-ffile-prefix-map=${fixed(cargoHome)}=/cargo"
        val cflags = System.getenv("CFLAGS")?.takeIf { it.isNotBlank() }?.let { "$it $prefixMap" } ?: prefixMap
        environment("CFLAGS", cflags)
    }
    commandLine(
        listOf("cargo", "ndk") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", rustJniLibs.get().asFile.absolutePath, "build", "--release", "--lib")
    )
}

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates the Kotlin binding for comms-crypto from the compiled library"
    dependsOn(cargoNdkBuild)
    workingDir = rustCrate
    inputs.dir(rustJniLibs)
    outputs.dir(rustBindings)
    // The binding metadata is architecture-independent; the arm64 library is read.
    commandLine(
        "cargo", "run", "--quiet", "--release", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", rustJniLibs.get().file("arm64-v8a/libclairix_comms_crypto.so").asFile.absolutePath,
        "--language", "kotlin", "--out-dir", rustBindings.get().asFile.absolutePath
    )
}

tasks.named("preBuild") { dependsOn(uniffiBindgen) }

// The relay list the app starts with: relays/relays.json at the repository's
// root, the same file the Relay list workflow signs and publishes. Newer
// copies come signed from the `relays` release (comms/RelayDirectory.kt).
// So does the asset key list, relays/keys.json (nav/AssetKeys.kt): the copy
// built in is the oldest list the app will take, whatever it is handed later.
val relayListAssets = layout.buildDirectory.dir("generated/relayList")
val copyRelayList by tasks.registering(Copy::class) {
    from(rootProject.file("../relays/relays.json"), rootProject.file("../relays/keys.json"))
    into(relayListAssets)
}
tasks.named("preBuild") { dependsOn(copyRelayList) }

// ── Third-party notices ────────────────────────────────────────────────────
//
// Settings > About > Third-party licences shows assets/third_party_licences.json,
// which thirdPartyNotices<Variant> writes for each build from:
//   - the variant's Android libraries and the licences their POMs declare, as
//     the AboutLibraries plugin lists them (prepareLibraryDefinitions<Variant>);
//   - notices/rust-crates.json: cargo-about's list of the crates compiled into
//     libclairix_comms_crypto.so, with each crate's own licence file
//     (comms-crypto/about.toml; .github/workflows/android.yml regenerates it
//     and fails when it differs from what is committed);
//   - notices/notices.json and notices/licences/: the licence texts the POMs
//     only name, and the native code inside two libraries that no POM lists.
// The build fails when a library has no licence or a licence has no text to
// ship. Nothing in the output depends on the time or the machine, and every
// list is in a fixed order, so two builds of one commit write the same bytes:
// the reproducible-build comparison in android.yml covers the asset with the
// rest of the APK.

aboutLibraries {
    // Licence data from the POMs only: nothing fetched over the network while
    // building, which could differ from one build to the next.
    offlineMode.set(true)
    collect {
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
    export {
        // No generation date in the output.
        includeMetaData.set(false)
    }
}

abstract class ThirdPartyNoticesTask : DefaultTask() {

    /** The AboutLibraries list of this variant's Android libraries. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidLibraries: RegularFileProperty

    /** app/notices: notices.json, licences/ and rust-crates.json. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = noticesDir.get().asFile
        @Suppress("UNCHECKED_CAST")
        fun obj(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
        fun list(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()
        fun str(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }
        fun sha(s: String): String = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)

        val slurper = groovy.json.JsonSlurper()
        val config = obj(slurper.parse(dir.resolve("notices.json")))
        val texts = obj(config["licenceTexts"])
        val byUrl = obj(config["licenceUrls"])
        val linked = obj(config["linkedOnly"])
        val overrides = obj(config["libraryLicences"])
        // Licences whose text carries the project's own copyright line (BSD,
        // MIT) are matched per library: a generic text would drop that line.
        val perLibrary = obj(config["libraryTexts"])
        val problems = mutableListOf<String>()

        // Licences shown once each: keyed by id and a hash of the text, so the
        // same text from several libraries or crates appears once.
        val licences = TreeMap<String, Map<String, Any?>>()
        fun withText(id: String, name: String, url: String?, text: String): String {
            val key = "$id-${sha(text)}"
            licences.getOrPut(key) { linkedMapOf("id" to id, "name" to name, "url" to url, "text" to text) }
            return key
        }
        // A licence notices.json knows by id, with its text, or by name and link
        // alone (terms that are not an open-source licence); null when it knows none.
        fun known(id: String): String? {
            val t = obj(texts[id])
            if (t.isNotEmpty()) {
                val body = str(t["text"]) ?: list(t["files"]).joinToString("\n\n") {
                    dir.resolve("licences/$it").readText(Charsets.UTF_8).trimEnd()
                }
                return withText(id, str(t["name"]) ?: id, str(t["url"]), body)
            }
            val l = obj(linked[id])
            if (l.isNotEmpty()) {
                licences.getOrPut(id) { linkedMapOf("id" to id, "name" to (str(l["name"]) ?: id), "url" to str(l["url"])) }
                return id
            }
            return null
        }

        val components = mutableListOf<Map<String, Any?>>()

        // Android libraries, with the licences their POMs declare.
        val about = obj(slurper.parse(androidLibraries.get().asFile))
        val aboutLicences = obj(about["licenses"])
        for (lib in list(about["libraries"]).map { obj(it) }) {
            val id = str(lib["uniqueId"]) ?: continue
            val declared = list(lib["licenses"]).mapNotNull { str(it) }
            val resolved = if (declared.isEmpty()) {
                val chosen = list(overrides[id]).mapNotNull { str(it) }
                if (chosen.isEmpty()) problems += "$id declares no licence: add it under libraryLicences in app/notices/notices.json"
                chosen.mapNotNull { lid -> known(lid) ?: run { problems += "$id: licence $lid is not in app/notices/notices.json"; null } }
            } else {
                declared.mapNotNull { key ->
                    val lic = obj(aboutLicences[key])
                    val spdx = str(lic["spdxId"])
                    val url = str(lic["url"])
                    val own = str(obj(perLibrary[id])[spdx ?: key])
                    own?.let { known(it) } ?: spdx?.let { known(it) } ?: url?.let { u -> str(byUrl[u])?.let { known(it) } } ?: run {
                        problems += "$id: licence \"${str(lic["name"]) ?: "?"}\" (${spdx ?: url ?: key}) has no text to ship: add it to app/notices/notices.json"
                        null
                    }
                }
            }
            val organisation = str(obj(lib["organization"])["name"])
            val developers = list(lib["developers"]).mapNotNull { str(obj(it)["name"]) }.distinct().joinToString(", ")
            components += linkedMapOf(
                "origin" to "android", "name" to (str(lib["name"]) ?: id), "id" to id, "version" to str(lib["artifactVersion"]),
                "by" to (organisation ?: developers.ifEmpty { null }), "website" to str(lib["website"]), "licences" to resolved.distinct(),
            )
        }

        // Native code compiled into two of those libraries, which no POM lists.
        for (n in list(config["native"]).map { obj(it) }) {
            val name = str(n["name"]) ?: continue
            val resolved = list(n["licences"]).mapNotNull { str(it) }.mapNotNull { lid ->
                known(lid) ?: run { problems += "native $name: licence $lid is not in app/notices/notices.json"; null }
            }
            if (resolved.isEmpty()) problems += "native $name has no licence"
            components += linkedMapOf(
                "origin" to "native", "name" to name, "id" to name, "in" to str(n["in"]),
                "website" to str(n["website"]), "note" to str(n["note"]), "licences" to resolved,
            )
        }

        // The Rust crates in libclairix_comms_crypto.so, each with its own licence file.
        val rust = obj(slurper.parse(dir.resolve("rust-crates.json")))
        val crates = TreeMap<String, Pair<Map<String, Any?>, TreeSet<String>>>()
        for (l in list(rust["licences"]).map { obj(it) }) {
            val lid = str(l["id"]) ?: continue
            val text = (l["text"] as? String)?.trimEnd()
            if (text.isNullOrBlank()) { problems += "rust-crates.json: a $lid entry has no text"; continue }
            val key = withText(lid, str(l["name"]) ?: lid, null, text)
            for (c in list(l["crates"]).map { obj(it) }) {
                val name = str(c["name"]) ?: continue
                val version = str(c["version"])
                crates.getOrPut("$name $version") {
                    linkedMapOf<String, Any?>("origin" to "rust", "name" to name, "id" to name, "version" to version) to TreeSet()
                }.second += key
            }
        }
        for ((entry, keys) in crates.values) components += entry + ("licences" to keys.toList())

        if (problems.isNotEmpty()) {
            throw GradleException("The third-party notices are incomplete:\n  " + problems.distinct().joinToString("\n  "))
        }

        val origins = listOf("android", "native", "rust")
        val sorted = components.sortedWith(
            compareBy<Map<String, Any?>>(
                { origins.indexOf(it["origin"] as String) },
                { (it["name"] as String).lowercase() },
                { it["id"] as String },
                { (it["version"] as? String).orEmpty() },
            )
        )
        val out = linkedMapOf(
            "format" to "clairix-third-party-licences",
            "version" to 1,
            "components" to sorted.map { c -> c.filterValues { it != null } },
            "licences" to licences.mapValues { (_, l) -> l.filterValues { it != null } },
        )
        val file = outputDirectory.get().asFile.resolve("third_party_licences.json")
        file.parentFile.mkdirs()
        file.writeText(groovy.json.JsonOutput.toJson(out), Charsets.UTF_8)
    }
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val notices = tasks.register<ThirdPartyNoticesTask>("thirdPartyNotices$suffix") {
            description = "Writes the third-party licences the app shows for the ${variant.name} build"
            dependsOn("prepareLibraryDefinitions$suffix")
            androidLibraries.set(layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/res/raw/aboutlibraries.json"))
            noticesDir.set(layout.projectDirectory.dir("notices"))
        }
        // AGP sets the output directory and packs what is written there as assets.
        variant.sources.assets?.addGeneratedSourceDirectory(notices, ThirdPartyNoticesTask::outputDirectory)
    }
}

// The JVM unit tests load the same crate built for this machine, so the relay
// round-trip tests exercise the real Olm and sealing code rather than a stand-in.
val rustHostLib = rustCrate.resolve("target/release")
val cargoHostBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds comms-crypto for the build machine, for the JVM unit tests"
    workingDir = rustCrate
    inputs.dir(rustCrate.resolve("src"))
    inputs.files(rustCrate.resolve("Cargo.toml"), rustCrate.resolve("Cargo.lock"))
    outputs.dir(rustHostLib)
    commandLine("cargo", "build", "--release", "--lib")
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    systemProperty("jna.library.path", rustHostLib.absolutePath)
    // The relay round-trip tests run only when a relay and enrollment secret are
    // given in the environment (CLAIRIX_RELAY_URL, CLAIRIX_ENROLL_SECRET); they are
    // forwarded from the build's own environment and never stored in the repo.
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

/**
 * The asset signing public key, committed as android/asset-signing.pub by
 * tools/signing/keygen.py. See tools/signing/README.md.
 */
val assetPublicKey: String = rootProject.file("asset-signing.pub").takeIf { it.isFile }?.readText()?.trim().orEmpty()

android {
    namespace = "app.clairix"
    compileSdk = 36

    // The NDK cargo-ndk builds with, so the Android Gradle plugin has a strip
    // tool. Without one it packages every native library as it is, debug info
    // and symbol tables included ("Unable to strip the following libraries");
    // 3.0.0 beta 2 shipped that way. Stripping also makes the libraries the
    // same from one build to the next: LLVM numbers the functions it outlines
    // for size in an order that varies, and only the local symbol names
    // differ, which the strip removes.
    resolveNdkDir()?.let { ndk ->
        ndkPath = ndk.absolutePath
        // The plugin checks the path against its own default ndkVersion and
        // refuses a mismatch ("[CXX1100] android.ndkVersion is [27.0.x] but
        // android.ndkPath refers to a different version"), so it is told the
        // pinned version, which resolveNdkDir has already checked is there.
        ndkVersion = pinnedNdkVersion
    }

    // No dependency-metadata block in the APK or bundle: it is a Google-signed
    // blob nobody but Google can read, and a build that is to be reproduced
    // should carry nothing that cannot be accounted for.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "app.clairix"
        minSdk = 31
        targetSdk = 36
        // The asset signing public key (tools/signing): what the app checks
        // downloaded maps, work zones and release checksums against. Empty in
        // a checkout that has not run keygen; a release build refuses that below.
        buildConfigField("String", "ASSET_PUBKEY", "\"$assetPublicKey\"")
        versionCode = 28
        versionName = "3.0.0-beta.8"
        // The emulator smoke test (src/androidTest, run by .github/workflows/emulator.yml).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two flavour dimensions, edition first, so variants read <edition><Tier>:
    //
    // edition — where the build is distributed:
    //   play   Google Play. No NFC card emulation and no card vault (the
    //          HostApduService, its manifest entry and AID resource are not in
    //          this build at all) and no ClairixCoin. Everything else is the same.
    //   full   Sideloaded from clairix.app and GitHub Releases: everything,
    //          including the card vault with card emulation and ClairixCoin.
    //   foss   For F-Droid: the full edition without Google Play services or
    //          Firebase. It takes the full edition's code and manifests (src/full,
    //          and src/fullSuite for its suite; see sourceSets below) and leaves out
    //          src/gms, the one place that uses Google's fused location client:
    //          its own src/foss supplies no such client, so every position comes
    //          from Android's own location service, the path the other editions
    //          take on a phone without Play services. play-services-location is a
    //          dependency of full and play only, and .github/workflows/android.yml
    //          fails the build if a com.google.android.gms or com.google.firebase
    //          artifact reaches this edition's runtime classpath or classes.
    //
    // tier — how much of the app a build carries:
    //   suite  the whole app: scanner, map, cell/NFC/Wi-Fi/device tabs, tools, COMMS
    //   comms  COMMS only: encrypted messages, calls, groups and SOS, with the safety
    //          pages (duress phrase, fake call, Wipe Clairix); no scanner or tools
    //
    // Variants built: playSuite (the Play upload), fullSuite, fullComms, and
    // fossSuite and fossComms for F-Droid. The comms-only build is handed out
    // directly, never through Play, so playComms is switched off below.
    //
    // A comms install upgrades to the full edition's suite in place (the same
    // applicationId and signing key) and keeps its Clairix number, contacts and
    // message history. The comms manifest (src/main) declares only what COMMS
    // needs; the scanner's permissions and components are added by
    // src/suite/AndroidManifest.xml, and the card-emulation service only by
    // src/fullSuite/AndroidManifest.xml. The code follows the same lines: Drive
    // mode (SDR, radio scanner, ADS-B, OBD-II), the NFC tab and the card vault
    // are in src/suite and card emulation in src/fullSuite, reached from the
    // shared code only through TierShell (src/main), which src/suite and
    // src/comms each implement as `object Tier`, so the comms build is compiled
    // without them (release.yml checks its R8 mapping). The code reads FULL_ACCESS to decide
    // which screens exist, CARD_VAULT for the vault and card emulation,
    // CLAIRIX_COIN for the coin balance, payments and their relay requests, and
    // LAUNCHER_DISGUISE for discreet mode's neutral launcher entries ("Compass",
    // "Notes"), which Google Play's policy does not allow: the Play edition keeps
    // the Clairix entry and src/play/AndroidManifest.xml removes the other two.
    flavorDimensions += listOf("edition", "tier")
    productFlavors {
        create("play") {
            dimension = "edition"
            buildConfigField("boolean", "PLAY_SERVICES", "true")
            buildConfigField("boolean", "CARD_VAULT", "false")
            buildConfigField("boolean", "CLAIRIX_COIN", "false")
            buildConfigField("boolean", "LAUNCHER_DISGUISE", "false")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "PLAY_SERVICES", "true")
            buildConfigField("boolean", "CARD_VAULT", "true")
            buildConfigField("boolean", "CLAIRIX_COIN", "true")
            buildConfigField("boolean", "LAUNCHER_DISGUISE", "true")
        }
        create("foss") {
            dimension = "edition"
            // PLAY_SERVICES false: no Google Play services library in this build, so
            // the screens say positions come from Android's own location service.
            buildConfigField("boolean", "PLAY_SERVICES", "false")
            buildConfigField("boolean", "CARD_VAULT", "true")
            buildConfigField("boolean", "CLAIRIX_COIN", "true")
            buildConfigField("boolean", "LAUNCHER_DISGUISE", "true")
        }
        create("suite") {
            dimension = "tier"
            buildConfigField("boolean", "FULL_ACCESS", "true")
        }
        create("comms") {
            dimension = "tier"
            versionNameSuffix = "-comms"
            buildConfigField("boolean", "FULL_ACCESS", "false")
        }
    }

    // Release signing comes from the environment, never from the repository:
    //
    //   ANDROID_KEYSTORE_FILE      path to the keystore (.jks / .keystore)
    //   ANDROID_KEYSTORE_PASSWORD  the keystore's password
    //   ANDROID_KEY_ALIAS          the key's alias inside it
    //   ANDROID_KEY_PASSWORD       the key's password (defaults to the keystore's)
    //
    // The release workflow decodes the ANDROID_KEYSTORE_BASE64 secret to a file
    // under the runner's temp directory and sets these. Without them release
    // builds come out unsigned, as before, and can be signed afterwards.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    signingConfigs {
        if (releaseKeystore != null) {
            if (!releaseKeystore.isFile) throw GradleException("ANDROID_KEYSTORE_FILE points at $releaseKeystore, which is not a file")
            fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("ANDROID_KEYSTORE_FILE is set but $name is not")
            create("release") {
                storeFile = releaseKeystore
                storePassword = required("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = required("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // android.util.Log and friends return defaults on the JVM instead of throwing.
        unitTests.isReturnDefaultValues = true
        // The app's merged resources, so the Robolectric tests of the text the screens
        // show (BatteryTextTest and the like) read the real strings.xml.
        unitTests.isIncludeAndroidResources = true
    }

    // Android Lint, run by .github/workflows/android.yml (lintFullSuiteDebug):
    // every warning is an error and stops the build. What lint found when this
    // was switched on is listed in lint-baseline.xml and tolerated until it is
    // fixed; anything new fails. updateLintBaselineFullSuiteDebug rewrites the
    // list: run it to drop what was fixed, not to hide something new.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
        // OldTargetApi compares targetSdk with the newest platform installed, not
        // with what this toolchain can build. GitHub's runner image carries API 37,
        // but Android Gradle plugin 8.x stops at 36 (37 needs 9.x, as does
        // sqlcipher-android 4.18; see the dependency below). So the reminder is
        // shown in every report without failing it; take this line out with the
        // move to AGP 9 and targetSdk 37.
        informational += "OldTargetApi"
        // NewerVersionAvailable asks Maven Central, at the moment lint runs, whether
        // any dependency has a newer release, so its answer changes whenever a
        // library publishes: on 3 November okhttp 5.5.1 failed every build with no
        // change in this repository. Updates are proposed by Dependabot
        // (.github/dependabot.yml, weekly), so lint reports them without failing.
        informational += "NewerVersionAvailable"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rustJniLibs)
            kotlin.srcDir(rustBindings)
            assets.srcDir(relayListAssets)
        }
        // Google's fused location client (EditionLocation), in the two editions
        // that carry Google Play services. The foss edition has its own EditionLocation.
        getByName("full") { kotlin.srcDir("src/gms/java") }
        getByName("play") { kotlin.srcDir("src/gms/java") }
        // The foss edition is the full edition without Google Play services: the
        // same direct SOS text (src/full) and, for its suite, the same card vault
        // with card emulation (src/fullSuite), from the same files.
        getByName("foss") {
            kotlin.srcDir("src/full/java")
            manifest.srcFile("src/full/AndroidManifest.xml")
        }
        maybeCreate("fossSuite").apply {
            kotlin.srcDir("src/fullSuite/java")
            res.srcDir("src/fullSuite/res")
            manifest.srcFile("src/fullSuite/AndroidManifest.xml")
        }
    }

    // The three ABIs the Rust crate is built for; anything else has no crypto library.
    defaultConfig {
        ndk { abiFilters += rustAbis }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // Material icons for the navigation shell and the screens. Only the core set:
    // the handful of others the app draws are carried in ClairixIcons.kt.
    implementation("androidx.compose.material:material-icons-core")

    // Google's fused location client, in the full and Play editions only (src/gms).
    // The foss edition, for F-Droid, is built without it; see productFlavors.
    "fullImplementation"("com.google.android.gms:play-services-location:21.3.0")
    "playImplementation"("com.google.android.gms:play-services-location:21.3.0")

    // Hidden-camera sweep — CameraX drives the torch-on lens-glint analyser and the
    // front-camera IR-illuminator view. Frames are read from the Y plane in process
    // and never stored or transmitted.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Live tile map — renders OpenStreetMap tiles. The network is used only for tile
    // downloads, which expose the device's IP and the viewed area to the tile server;
    // no detection data is transmitted.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Card vault — BiometricPrompt and AppCompatActivity for vault unlock
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // COMMS — end-to-end encrypted messaging between Clairix apps.
    // OkHttp talks to the owner's relay (signed HTTPS and a WebSocket); JNA is
    // how the UniFFI binding reaches the Rust crypto library; zxing renders and
    // scans the pairing QR codes; the UnifiedPush connector wakes the app through
    // a distributor of the owner's choosing (for example the ntfy app). No Google
    // service is involved anywhere.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // Every page of the comms database encrypted; see comms/CommsDb.kt. 4.18.0 and
    // later declare minCompileSdk 37 in their AAR metadata (checked against 4.18.0:
    // "requires ... to compile against version 37 or later"), which needs an
    // Android Gradle plugin of the 9.x line; the 8.x line stops at compileSdk 36.
    // 4.17 is the newest release this toolchain can build.
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    // SQLCipher's database and open-helper classes implement androidx.sqlite's
    // support interfaces, which its POM lists at runtime scope only; the Kotlin
    // compiler needs them on the compile classpath to resolve the supertypes.
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.unifiedpush.android:connector:3.3.5")
    // Calls: prebuilt libwebrtc (audio over DTLS-SRTP, signalled through the encrypted envelopes).
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // The emulator smoke test: black-box, through UI Automator, so it drives the app
    // the way a person does and needs nothing inside it.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json: android.jar only carries stubs of it.
    testImplementation("org.json:json:20240303")
    // The desktop JNA jar carries the native dispatch library for the build machine.
    testImplementation("net.java.dev.jna:jna:5.19.1")
    // A real Context, SharedPreferences and SQLite for the few JVM tests that need the
    // framework (marked @RunWith(RobolectricTestRunner::class)); every other test keeps
    // the stubbed android.jar above. 4.14 is the first release that knows compileSdk 35;
    // its tests run against SDK 34 (src/test/resources/robolectric.properties), because
    // Robolectric's SDK 35 needs Java 21 and this build runs on 17.
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Robolectric's Android runtime, resolved by Gradle like any other dependency (and
// cached with it) instead of downloaded by Robolectric itself while the tests run,
// which made every run depend on Maven Central answering at that moment (it
// rate-limits). The tests then run Robolectric offline against this copy. The
// versions are the ones robolectric 4.14.1 pairs with SDK 34, which the tests
// run on (src/test/resources/robolectric.properties), and SDK 35, the newest it
// knows: the app targets 36, past it, and the offline runs need no other jar.
// Two configurations: one module in two versions in one configuration resolves to
// the newer alone.
val robolectricRuntime: Configuration by configurations.creating
val robolectricTarget: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    // The SDK Robolectric reads the app's resources against, whichever SDK the
    // tests run on: the target, or the newest it knows when the target is newer.
    robolectricTarget("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
// Links into Gradle's cache, not copies: the two jars are about 300 MB.
val copyRobolectricRuntime by tasks.registering {
    inputs.files(robolectricRuntime, robolectricTarget)
    outputs.dir(robolectricJars)
    doLast {
        val dir = robolectricJars.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        (robolectricRuntime.files + robolectricTarget.files).forEach { jar ->
            Files.createSymbolicLink(dir.toPath().resolve(jar.name), jar.toPath())
        }
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}

// detekt, run by .github/workflows/android.yml with its default rules and
// android/config/detekt.yml over every source set (main, the flavours, the JVM
// and device tests). What it found when
// it was switched on is listed in detekt-baseline.xml (written by detektBaseline)
// and tolerated until it is fixed; anything new fails.
detekt {
    buildUponDefaultConfig = true
    // What differs from detekt's defaults, and why: Compose functions, layout
    // numbers and line length are house style, not defects.
    config.setFrom(rootProject.file("config/detekt.yml"))
    parallel = true
    source.setFrom("src")
    baseline = file("detekt-baseline.xml")
}

// detekt 1.23 is built with Kotlin 2.0 and refuses to run with another Kotlin
// compiler, which the Kotlin plugin here would hand it. Its own classpath alone
// gets the version it was built with (detekt's documented remedy); the app is
// compiled with the Kotlin plugin's version as before.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
        }
    }
}

// A CycloneDX bill of materials of the libraries in the release builds, which
// .github/workflows/release.yml attaches to every release: each library in the
// runtime classpath, with its version. The three variants release.yml publishes
// have the same dependencies, so the full edition's classpath stands for all of
// them. (The foss edition, which release.yml does not build, has the same less
// play-services-location and what it pulls in.)
// The Rust crates inside the crypto library get a bill of their own from
// cargo-cyclonedx in the same workflow.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("fullSuiteReleaseRuntimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentGroup.set("app.clairix")
    componentName.set("clairix")
    componentVersion.set(android.defaultConfig.versionName)
    includeBomSerialNumber.set(false)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/clairix-android.cdx.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/clairix-android.cdx.xml"))
}

// The comms-only build is never published on Google Play, so the Play edition
// has no comms variant: playSuite, fullSuite, fullComms, fossSuite and fossComms
// are the five built.
androidComponents {
    beforeVariants(selector().withFlavor("edition" to "play").withFlavor("tier" to "comms")) { variant ->
        variant.enable = false
    }
}

// A release that cannot verify what it downloads must not be built.
tasks.configureEach {
    if (name.matches(Regex("(assemble|bundle)\\w*Release"))) {
        doFirst {
            if (assetPublicKey.isEmpty()) {
                throw GradleException("android/asset-signing.pub is missing: a release build needs the asset signing public key (see tools/signing/README.md)")
            }
        }
    }
}
