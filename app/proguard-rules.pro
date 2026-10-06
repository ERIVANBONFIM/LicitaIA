# Regras R8 do LicitaIA (release)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# kotlinx.serialization (regras oficiais; DTOs @Serializable ficam em core-data/db/Converters.kt e core-network)
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.licitaia.**$$serializer { *; }
-keepclassmembers class com.licitaia.** {
    *** Companion;
}
-keepclasseswithmembers class com.licitaia.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# Room: implementações geradas (<Database>_Impl) são resolvidas por nome em tempo de execução.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.licitaia.core.data.db.LicitaDatabase_Impl { *; }
-dontwarn androidx.room.paging.**

# Retrofit / OkHttp (core-network): assinaturas genéricas e interfaces de serviço.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn retrofit2.**

# Enums do domínio são persistidos pelo nome (Room TypeConverters / DataStore): não renomear.
-keepclassmembers enum com.licitaia.domain.model.** { *; }

# pdfbox-android: decodificador JPEG2000 opcional (nao incluido)
-dontwarn com.gemalto.jp2.**
