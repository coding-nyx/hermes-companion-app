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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hermes-companion-app"

include(":app")
include(":core-model")
include(":core-design")
include(":domain")
include(":data-local")
include(":data-remote")
include(":feature-connect")
include(":feature-threads")
include(":feature-chat")
include(":feature-profiles")
include(":feature-gateway")
include(":feature-device")
