// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    //kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("maven-publish")
}

// Global configuration for all subprojects
allprojects {
    group = "com.github.AltudePlatform"
    version = "0.1.0-alpha"
}

// Configure publishing for library modules
subprojects {
    apply(plugin = "maven-publish")
    
    // Only configure publishing for library modules
    plugins.withId("com.android.library") {
        configure<PublishingExtension> {
            publications {
                register<MavenPublication>("release") {
                    afterEvaluate {
                        from(components["release"])
                    }
                    
                    groupId = "com.github.AltudePlatform"
                    artifactId = project.name
                    version = "0.1.0-alpha"
                    
                    pom {
                        name.set("Altude ${project.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} SDK")
                        description.set("Altude ${project.name} module for Android")
                        url.set("https://github.com/AltudePlatform/AndroidSDK")
                        
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        
                        developers {
                            developer {
                                id.set("altudeplatform")
                                name.set("Altude Platform")
                                email.set("contact@altude.com")
                            }
                        }
                        
                        scm {
                            connection.set("scm:git:git://github.com/AltudePlatform/AndroidSDK.git")
                            developerConnection.set("scm:git:ssh://github.com:AltudePlatform/AndroidSDK.git")
                            url.set("https://github.com/AltudePlatform/AndroidSDK/tree/main")
                        }
                    }
                }
            }
        }
    }
}