# Release builds are minified and shrunk; debug builds are not.

# Strip every android.util.Log call from release: entries name contacts, calls,
# relay URLs and SOS events, and logcat is in every bug report.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# UniFFI binding to the comms-crypto crate: loaded by name through JNA, so the
# generated Kotlin and JNA itself must keep their names and members.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# osmdroid tile providers are looked up reflectively.
-keep class org.osmdroid.** { *; }

# WebRTC native bridge.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep source file and line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
