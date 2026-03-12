// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    //kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    `maven-publish`
}
val buildToolsVersion by extra("35.0.0")

// Configure maven-publish for all Android library subprojects (required by JitPack)
subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("com.android.library")) {
            apply(plugin = "maven-publish")
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        groupId = project.findProperty("group")?.toString() ?: "com.github.AltudePlatform"
                        artifactId = project.name
                        version = project.findProperty("version")?.toString() ?: "1.0"
                        afterEvaluate {
                            from(components["release"])
                        }
                    }
                }
            }
        }
    }
}


