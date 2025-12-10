plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.2.0"
}

android {
    namespace = "com.altude.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // AndroidX + Google
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom)) // Recommended: Use the BOM
    implementation(libs.androidx.runtime) // <-- Add this line
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Crypto
    implementation(libs.bouncycastle) {
        exclude(group = "com.ditchoom")
    }
    implementation(libs.diglol.crypto) {
        exclude(group = "com.ditchoom")
    }

    // Serialization
    implementation(libs.serialization.json) {
        exclude(group = "com.ditchoom")
    }
    implementation(libs.retrofit.serialization)
    implementation(libs.serialization.json.compat)

    // Solana & Metaplex
    implementation(libs.solana) {
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module = "kborsh")
    }
    implementation(libs.mpl.bubblegum) {
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module = "kborsh")
    }
    implementation(libs.mpl.token.metadata) {
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module = "kborsh")
    }

    implementation(libs.kborsh) {
        exclude(group = "com.ditchoom")
    }
    implementation(libs.buffer.jvm)

    // Wallets
    implementation(libs.bitcoinj)
    implementation(libs.hdwallet.bip39) {
        exclude(group = "org.bouncycastle")
    }

    // Security
    implementation(libs.security.crypto)

    // Auth
    implementation(libs.jwt)
    implementation("com.solanamobile:web3-solana:0.2.5")
}
