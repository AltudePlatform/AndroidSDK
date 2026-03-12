pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Toolchain auto-provisioning removed - use locally installed JDK 17
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
    }
}

rootProject.name = "altude-sdk"
include(":app")
include(":smart-account")
include(":nft")
include(":gasstation")
include(":vault")
include(":core")
