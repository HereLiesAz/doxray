# Project-level proguard rules. Active only when isMinifyEnabled = true.

# Retrofit / OkHttp / Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson serialized models
-keep class com.hereliesaz.doxray.**$* { *; }
-keep class com.hereliesaz.doxray.api.*$Result { *; }
-keep class com.hereliesaz.doxray.api.*$*Response { *; }
-keep class com.hereliesaz.doxray.api.*$*Result { *; }
-keep class com.hereliesaz.doxray.api.*$ImageResult { *; }
-keep class com.hereliesaz.doxray.api.*$SearchMetadata { *; }

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Meta Wearables DAT SDK (closed-beta — keep all)
-keep class com.facebook.wearables.** { *; }
-dontwarn com.facebook.wearables.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
