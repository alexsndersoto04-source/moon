# MoonShare — reglas de ProGuard/R8

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moonshare.**$$serializer { *; }
-keepclassmembers class com.moonshare.** {
    *** Companion;
    *** Serializer;
}
-keepclasseswithmembers class com.moonshare.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-dontwarn androidx.compose.**

# Componentes referenciados solo desde el manifest
-keep class com.moonshare.** extends android.app.Service
-keep class com.moonshare.** extends android.app.Application
-keep class com.moonshare.** extends android.content.BroadcastReceiver

# Modelo de datos del protocolo (se serializa por reflexion en fallbacks)
-keep class com.moonshare.net.ProtocolDto { *; }
