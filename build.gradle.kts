plugins {
    // API 37 requires AGP >= 9.1.1; AGP 9.1.1 supplies Kotlin 2.2.10.
    id("com.android.application") version "9.1.1" apply false
    // Built-in Kotlin replaces org.jetbrains.kotlin.android in AGP 9.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}
