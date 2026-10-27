# MediaPipe Tasks & Nearby are reflective/JNI heavy: keep their surfaces intact.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.gms.nearby.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-keepclasseswithmembernames class * { native <methods>; }
