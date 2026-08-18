-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.aifih.volare.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.aifih.volare.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
