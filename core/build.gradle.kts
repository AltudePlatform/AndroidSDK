plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") version "2.2.0"
}

android {
    namespace = "com.altude.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation ("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Required for crypto (ed25519, sha256, etc.)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
//
    implementation("foundation.metaplex:solana:0.2.10")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8")
    implementation("com.solanamobile:web3-solana:0.2.5")
    implementation("com.solanamobile:rpc-core:0.2.8")
    implementation("com.solanamobile:rpc-solana:0.2.8")

    //implementation("io.github.funkatronics:multimult:0.2.4")

    implementation("com.diglol.crypto:pkc:0.2.0")
//
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
   // implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
}