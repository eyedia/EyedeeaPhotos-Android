pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)  // Changed this line
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Eyedeea Photos"
include(":app")
