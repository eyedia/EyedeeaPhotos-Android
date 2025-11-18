// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Remove ALL repository declarations from here
// They should only be in settings.gradle.kts

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
