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

# 错误详情里会显示异常的类名（NetError.transport）：自家的这个不改名，否则正式包里只剩一两个字母
-keepnames class app.echoread.tts.NoAudioException
