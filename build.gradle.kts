// build.gradle.kts (الجذر)
plugins {
    id("com.android.application") version "8.6.0" apply false
    // 👇 ترقية إصدار Kotlin إلى 2.2.0
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false 
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false 
}
