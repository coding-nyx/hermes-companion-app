# Hermes Companion release rules (A7.7). R8 full mode, proguard-android-optimize.txt as base.

# --- Crash reports stay readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# --- kotlinx.serialization (core-model @Serializable data classes, data-remote codecs)
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.hermes.companion.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# --- Room (entities/DAOs are reflected by the generated impls; consumer rules cover most)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class app.hermes.companion.data.local.** { *; }
-dontwarn androidx.room.paging.**

# --- androidx.security-crypto / Tink (protobuf-lite reflection)
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# --- OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# --- Android components referenced only from manifests / PendingIntents
-keep class app.hermes.companion.MainActivity { *; }
-keep class app.hermes.companion.CompanionApp { *; }
-keep class app.hermes.companion.StayConnectedService { *; }
-keep class app.hermes.companion.voice.WakeWordService { *; }
-keep class app.hermes.companion.device.CompanionAccessibilityService { *; }
-keep class app.hermes.companion.device.HandsService { *; }
-keep class app.hermes.companion.device.DisarmReceiver { *; }

# --- Enums serialized by name over the wire
-keepclassmembers enum app.hermes.companion.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
