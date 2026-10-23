# JNI entry points are looked up by name.
-keep class org.swatonly.xmrvault.Native { native <methods>; }
# Strip logging entirely.
-assumenosideeffects class android.util.Log { *; }
