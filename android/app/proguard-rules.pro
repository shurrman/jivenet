# Go-биндинги — gomobile генерирует классы dnsttmobile.*, которые
# вызываются через JNI. ProGuard не должен их удалять.
-keep class dnsttmobile.** { *; }
-keep class go.** { *; }

# KotlinX Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
