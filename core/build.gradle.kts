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
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.1.0")

    // Required for crypto (ed25519, sha256, etc.)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }
//
  
    //implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8")
    implementation("com.solanamobile:web3-solana:0.2.5"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }
    implementation("com.solanamobile:rpc-core:0.2.7"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }
    implementation("com.solanamobile:rpc-solana:0.2.8"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }

    //implementation("io.github.funkatronics:multimult:0.2.4")

    implementation("com.diglol.crypto:pkc:0.2.0"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }
//
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0"){
        exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }

    // KMP Solana + Borsh support for Android
    //implementation("com.funkatronics:kborsh:0.2.4") // Confirm this is the android-compatible variant
    //implementation("io.github.funkatronics:multimult:0.2.4")
   // implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
    //implementation("com.ditchoom:buffer-android:0.2.4")
    implementation("com.squareup.okhttp3:okhttp:5.1.0"){
        //exclude(group = "com.ditchoom")
        //exclude(group = "io.github.funkatronics")
    }

    // Replace any existing ditchoom:buffer:jvm or core-jvm with android version:
    //implementation("com.ditchoom:buffer-android:0.4.3")
    // implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
    //implementation("com.ditchoom:buffer-android:1.4.2")

    implementation("foundation.metaplex:solana:0.2.10"){
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module="kborsh" )

    }
    //implementation("com.ditchoom:buffer-jvm:1.4.2")
    implementation("io.github.funkatronics:kborsh-jvm:0.1.1"){
        exclude(group = "com.ditchoom")
    }
    implementation("com.ditchoom:buffer-jvm:1.4.2")

    //implementation("io.github.funkatronics:multimult-jvm:0.2.4")
    //implementation("foundation.metaplex:solana-jvm:0.2.10")

//    implementation("org.bitcoinj:bitcoinj-core:0.15.10")  // For BIP39 seed
//    implementation("org.bouncycastle:bcprov-jdk15to18:1.70") // For Ed25519
    implementation("org.bitcoinj:bitcoinj-core:0.16.2")
    //implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
    // https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk18on
    //implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("io.provenance.hdwallet:hdwallet-bip39:0.1.15"){
        exclude(group = "org.bouncycastle")
    }
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    //implementation("com.ditchoom:buffer-jvm:1.4.2") // Note: Group ID is com.ditchoom
    //implementation("com.solanamobile:seedvault-wallet-sdk:0.3.2")
//    implementation("com.ditchoom.buffer:bip39-jvm:1.4.2") // This one looks correct for BIP39
//    implementation("com.ditchoom.buffer:bip32-jvm:1.4.2") // This one looks correct for BIP32
    //implementation("com.solanamobile:seedvault-wallet-sdk:0.3.2")
    //implementation("com.ionspin.kotlin:multiplatform-crypto:0.1.0")

    //implementation("com.solanamobile:seedvault-wallet-sdk:0.3.2")


    //testImplementation("junit:junit:4.13.2")

}