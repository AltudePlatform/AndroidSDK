plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.altude.smartaccount"
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
    implementation(project(":core"))

    implementation ("com.squareup.retrofit2:retrofit:3.0.0")
    implementation ("com.squareup.retrofit2:converter-gson:3.0.0")
//    implementation("com.ditchoom:buffer-android:0.2.4")
//
//    //implementation("com.funkatronics:kborsh:0.2.4") // Confirm this is the android-compatible variant
//    implementation("io.github.funkatronics:multimult:0.2.4")
//    // implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
//    //implementation("com.ditchoom:buffer-android:1.4.2")
//
//    testImplementation(platform("org.junit:junit-bom:5.13.4"))
//    testImplementation("org.junit.jupiter:junit-jupiter")
//    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("foundation.metaplex:solana:0.2.10"){
        exclude(group = "com.ditchoom")
        exclude(group = "io.github.funkatronics", module="kborsh" )
    }
    //implementation("com.ditchoom:buffer-jvm:1.4.2")
    implementation("io.github.funkatronics:kborsh-jvm:0.1.1"){
        exclude(group = "com.ditchoom")
    }
    implementation("com.ditchoom:buffer-jvm:1.4.2")
}