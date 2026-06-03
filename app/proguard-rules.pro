-keep @com.google.dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *

# PostHog SDK — uses reflection for serialization
-keep class com.posthog.** { *; }
-dontwarn com.posthog.**

# kotlinx.serialization — keep generated serializers for DTO classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ch.rhosys.sbb.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class ch.rhosys.sbb.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**

# Lifecycle Compose — LocalLifecycleOwner provider stripped by R8 causes crash
-keep class androidx.lifecycle.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**
