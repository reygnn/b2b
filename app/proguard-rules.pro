# Spotify Companion ProGuard rules.
# Keep Retrofit interfaces.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes Signature
-keepattributes *Annotation*
