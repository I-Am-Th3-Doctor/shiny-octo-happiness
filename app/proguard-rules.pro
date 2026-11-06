# The Anthropic SDK (Calendar widget AI scheduling) reads and writes JSON with Jackson, through
# reflection on its model classes and the Kotlin module: keep them for release (R8) builds.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn org.slf4j.**
-dontwarn io.swagger.**
-dontwarn com.github.victools.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.opentelemetry.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
