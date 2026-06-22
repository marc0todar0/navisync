# Moshi reflection-based adapters
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
