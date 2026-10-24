# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.smartmanager.installments.**$$serializer { *; }
-keepclassmembers class com.smartmanager.installments.** { *** Companion; }
-keepclasseswithmembers class com.smartmanager.installments.** { kotlinx.serialization.KSerializer serializer(...); }
