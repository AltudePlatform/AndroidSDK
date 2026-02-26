plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") version "2.2.0"
}

android {
    namespace = "com.altude.vault"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Configure packaging for 16 KB page size alignment (required for Android 15+ on Google Play)
    packaging {
        jniLibs {
            pickFirsts.add("lib/x86_64/libargon2.so")
            pickFirsts.add("lib/arm64-v8a/libargon2.so")
            pickFirsts.add("lib/armeabi-v7a/libargon2.so")
            pickFirsts.add("lib/x86/libargon2.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.monitor)
    // Biometric authentication
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    implementation(project(":core"))
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Crypto
    implementation(libs.bouncycastle) {
        exclude(group = "com.ditchoom")
    }
    // Removed diglol.crypto (0.2.0 not 16 KB aligned) - using BouncyCastle instead

    // Solana & Metaplex
    api(libs.solana) {
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module = "kborsh")
        exclude(group = "de.mkammerer", module = "argon2-jvm")
    }

    // Use argon2-jvm-nolibs to avoid 16 KB alignment issues with native libraries
    implementation("de.mkammerer:argon2-jvm-nolibs:2.11")

    // Serialization
    implementation(libs.serialization.json) {
        exclude(group = "com.ditchoom")
    }
}
