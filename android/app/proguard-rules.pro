# kotlinx.serialization：保留 @Serializable 类的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.echoread.**$$serializer { *; }
-keepclassmembers class app.echoread.** { *** Companion; }
-keepclasseswithmembers class app.echoread.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / jsoup / juniversalchardet
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn org.mozilla.universalchardet.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
