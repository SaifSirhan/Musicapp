# R8 / ProGuard rules for the release build.
#
# Supabase (supabase-kt), kotlinx.serialization, Ktor and Credential Manager all
# load classes reflectively at runtime, so R8 must be told to keep them or the
# release APK crashes on launch while debug (no shrinking) works.

# Supabase
-keep class io.github.jan.supabase.** { *; }

# Ktor
-keep class io.ktor.** { *; }

# Credential Manager
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# R8 full mode with AGP's scoped optimization can repackage one half of a Kotlin
# stdlib multifile facade while leaving the other behind, breaking package access.
# Observed at class-init: StringsKt__StringBuilderKt (ca4) extending
# StringsKt__IndentKt (kotlin.text.o) -> IllegalAccessError. Pin them in place.
-keep class kotlin.text.StringsKt__* { *; }
