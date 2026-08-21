# Keep native loader bridge
-keep class com.winfex.native.** { *; }

# Keep model classes used by Moshi
-keep class com.winfex.model.** { *; }
-keepclassmembers class com.winfex.model.** { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn org.jetbrains.annotations.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.compressors.xz.XZCompressorInputStream { *; }
-keep class org.apache.commons.compress.archivers.tar.** { *; }
