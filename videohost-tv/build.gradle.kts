// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Compose Compiler plugin for Kotlin 2.0+ — replaces the old
    // composeOptions.kotlinCompilerExtensionVersion approach.
    // This is required because Kotlin 2.0 moved the Compose compiler
    // out of the Kotlin distribution into a standalone plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
