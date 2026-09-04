# SQLCipher ships native code reached only via JNI.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Firestore serialises the maps we hand it reflectively.
-keepattributes Signature
-keepattributes *Annotation*
