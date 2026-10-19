import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 compiles Kotlin itself; only the Compose compiler plugin is added.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Release signing material lives outside git. Without it the release build
// still assembles, just unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.mazecrypto.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "tr.com.berkkucukk.mazecrypto"
        minSdk = 26
        targetSdk = 37
        // Must never go backwards or repeat.
        versionCode = 4
        versionName = "0.5.1"
    }

    signingConfigs {
        create("release") {
            val store = keystoreProperties.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // minSdk 26: the v1 JAR signature buys nothing. v3 allows the key
                // to be rotated later without orphaning existing installs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // No Google-encrypted dependency blob in the signing block: the APK stays
    // inspectable and reproducible.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

val distDir = rootProject.layout.projectDirectory.dir("dist")
val appVersion = android.defaultConfig.versionName

val copyReleaseApk = tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(distDir)
    rename { "maze-crypto-$appVersion.apk" }
    // A checksum next to the APK, for `sha256sum -c` after downloading.
    doLast {
        val apk = distDir.file("maze-crypto-$appVersion.apk").asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        distDir.file("${apk.name}.sha256").asFile.writeText("$digest  ${apk.name}\n")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
