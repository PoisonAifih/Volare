-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.aifih.onthefly.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.aifih.onthefly.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
