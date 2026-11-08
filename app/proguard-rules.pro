# kotlinx.serialization keeps its own rules via the library; keep our serializable models' companions.
-keepclassmembers @kotlinx.serialization.Serializable class lol.sinist3r.yip.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Navigation routes are looked up by class.
-keep class lol.sinist3r.yip.navigation.** { *; }
# Bouncy Castle: only Ed25519 is used; let R8 drop the rest but keep what's reachable.
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
