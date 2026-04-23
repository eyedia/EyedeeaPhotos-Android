import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Manually load properties to bypass environment issues
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    val properties = Properties()
    properties.load(FileInputStream(localPropertiesFile))
    properties.forEach { (key, value) ->
        project.extra.set(key.toString(), value.toString())
    }
}

android {
    namespace = "com.eyediatech.eyedeeaphotos"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (project.extra.has("keyAlias") && project.extra.has("keystorePassword") && project.extra.has("keyPassword")) {
                storeFile = file("D:/Work/Eyedeea-Core/android/eyedeea_photos_v2.jks")
                storePassword = project.extra.get("keystorePassword") as String
                keyAlias = project.extra.get("keyAlias") as String
                keyPassword = project.extra.get("keyPassword") as String
            } else {
                // This block is left empty intentionally. The build will fail later with a clear message if signing is attempted without configuration.
            }
        }
    }

    flavorDimensions += "platform"

    productFlavors {
        create("firetv") {
            dimension = "platform"
            buildConfigField("boolean", "ENABLE_DOWNLOADS", "false")
        }
        create("mobile") {
            dimension = "platform"
            buildConfigField("boolean", "ENABLE_DOWNLOADS", "true")
            applicationIdSuffix = ".mobile"
        }
    }

    sourceSets {
        getByName("main") { 
            java.srcDirs("src/main/java")
        }
        getByName("firetv") {
            java.srcDirs("src/firetv/java")
            res.srcDirs("src/firetv/res")
            manifest.srcFile("src/firetv/AndroidManifest.xml")
        }
        getByName("mobile") {
            java.srcDirs("src/mobile/java")
            res.srcDirs("src/mobile/res")
        }
    }

    defaultConfig {
        applicationId = "com.eyediatech.eyedeeaphotos"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "BASE_URL", "\"https://eyedeeaphotos.eyediatech.com\"")
            buildConfigField("String", "VIEW_URL", "\"https://eyedeeaphotos.eyediatech.com/view\"")
            buildConfigField("String", "LOGIN_URL", "\"https://eyedeeaphotos.eyediatech.com/auth/login?device=android\"")
            buildConfigField("boolean", "ENABLE_WEB_CONSOLE_LOG", "false")
        }
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BASE_URL", "\"http://192.168.86.100:5174\"")
            buildConfigField("String", "VIEW_URL", "\"http://192.168.86.100:5174/view\"")
            buildConfigField("String", "LOGIN_URL", "\"http://192.168.86.100:5174/auth/login?device=android\"")
            buildConfigField("boolean", "ENABLE_WEB_CONSOLE_LOG", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.leanback:leanback:1.2.0-alpha04")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    // For Kotlin Symbol Processing (KSP) if you want to use it, but keeping it simple with kapt if it's already there, 
    // though kapt isn't explicitly in plugins. Let's use annotationProcessor for now or check plugins.
    // Actually, I should check if Kapt or KSP is applied. 
    // Based on plugins block: id("org.jetbrains.kotlin.android"), nothing else.
    // Let's add Kapt.

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // QR Code
    implementation("com.google.zxing:core:3.5.3")

    // Security for token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

tasks.register("buildAndCopyApks") {
    group = "Build"
    description = "Builds and copies release APKs for all platforms."
    dependsOn(tasks.named("assembleFiretvRelease"), tasks.named("assembleMobileRelease"))

    doLast {
        val destinationDir = rootProject.file("../../release")
        destinationDir.mkdirs()

        copy {
            from(file("build/outputs/apk/firetv/release")) {
                include("*.apk")
            }
            into(destinationDir)
            rename { "ep_f.apk" }
        }

        copy {
            from(file("build/outputs/apk/mobile/release")) {
                include("*.apk")
            }
            into(destinationDir)
            rename { "ep_a.apk" }
        }
    }
}

tasks.register("printMyProperties") {
    group = "debug"
    doLast {
        println("== Gradle Property Check ==")
        println("Has keystorePassword: " + project.extra.has("keystorePassword"))
        println("Has keyAlias: " + project.extra.has("keyAlias"))
        println("Has keyPassword: " + project.extra.has("keyPassword"))
        println("===========================")
    }
}
