import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // JVM screenshot tests of the Compose screens (no device): layoutlib renders
    // them; recordPaparazziFullSuiteDebug writes the PNGs to src/test/snapshots.
    id("app.cash.paparazzi")
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
 * The NDK cargo-ndk should use: ANDROID_NDK_HOME if set, else the runner's
 * ANDROID_NDK_LATEST_HOME, else the newest NDK installed under the SDK.
 * cargo-ndk fails with "Error detecting NDK version for path" when it is
 * handed nothing, which is what an unset variable looks like in CI.
 */
fun resolveNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
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
    return sdk?.resolve("ndk")?.listFiles { f -> f.isDirectory && f.resolve("source.properties").isFile }
        ?.maxByOrNull { it.name }
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
            ?: throw GradleException("No Android NDK found: set ANDROID_NDK_HOME or install one under \$ANDROID_HOME/ndk")
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        logger.lifecycle("cargo-ndk using NDK at ${ndk.absolutePath}")
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
    compileSdk = 35

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
        // version that is actually there.
        val props = Properties()
        ndk.resolve("source.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
        props.getProperty("Pkg.Revision")?.trim()?.takeIf { it.isNotEmpty() }?.let { ndkVersion = it }
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
        targetSdk = 35
        // The asset signing public key (tools/signing): what the app checks
        // downloaded maps, work zones and release checksums against. Empty in
        // a checkout that has not run keygen; a release build refuses that below.
        buildConfigField("String", "ASSET_PUBKEY", "\"$assetPublicKey\"")
        versionCode = 23
        versionName = "3.0.0-beta.3"
    }

    // Two flavour dimensions, edition first, so variants read <edition><Tier>:
    //
    // edition — where the build is distributed:
    //   play   Google Play. No NFC card emulation and no card vault (the
    //          HostApduService, its manifest entry and AID resource are not in
    //          this build at all) and no ClairixCoin. Everything else is the same.
    //   full   Sideloaded from clairix.app and GitHub Releases: everything,
    //          including the card vault with card emulation and ClairixCoin.
    //
    // tier — how much of the app a build carries:
    //   suite  the whole app: scanner, map, cell/NFC/Wi-Fi/device tabs, tools, COMMS
    //   comms  COMMS only: encrypted messages, calls, groups and SOS, nothing else
    //
    // Variants built: playSuite (the Play upload), fullSuite and fullComms. The
    // comms-only build is handed out directly, never through Play, so playComms
    // is switched off below.
    //
    // A comms install upgrades to the full edition's suite in place (the same
    // applicationId and signing key) and keeps its Clairix number, contacts and
    // message history. The comms manifest (src/main) declares only what COMMS
    // needs; the scanner's permissions and components are added by
    // src/suite/AndroidManifest.xml, and the card-emulation service only by
    // src/fullSuite/AndroidManifest.xml. The code reads FULL_ACCESS to decide
    // which screens exist, CARD_VAULT for the vault and card emulation, and
    // CLAIRIX_COIN for the coin balance, payments and their relay requests.
    flavorDimensions += listOf("edition", "tier")
    productFlavors {
        create("play") {
            dimension = "edition"
            buildConfigField("boolean", "CARD_VAULT", "false")
            buildConfigField("boolean", "CLAIRIX_COIN", "false")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "CARD_VAULT", "true")
            buildConfigField("boolean", "CLAIRIX_COIN", "true")
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
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rustJniLibs)
            kotlin.srcDir(rustBindings)
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

    implementation("com.google.android.gms:play-services-location:21.3.0")

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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Every page of the comms database encrypted; see comms/CommsDb.kt. 4.18 and
    // later demand compileSdk 36 and so a newer Android Gradle plugin; 4.17 is
    // the newest that builds against 35.
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

    testImplementation("junit:junit:4.13.2")
    // The real org.json: android.jar only carries stubs of it.
    testImplementation("org.json:json:20240303")
    // The desktop JNA jar carries the native dispatch library for the build machine.
    testImplementation("net.java.dev.jna:jna:5.19.1")
}

// The comms-only build is never published on Google Play, so the Play edition
// has no comms variant: playSuite, fullSuite and fullComms are the three built.
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
