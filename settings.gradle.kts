pluginManagement {
    repositories {
        // Local repo with ARM64 aapt2 (Termux build) replacing x86_64-only binary
        maven { url = uri("/data/data/com.termux/files/home/m2repo") }
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("/data/data/com.termux/files/home/m2repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "drawit"
include(":app")
