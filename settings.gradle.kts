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

rootProject.name = "SwipeDelete"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:storage")
include(":core:media")
include(":core:designsystem")
include(":feature:deck")
include(":feature:viewer")
include(":feature:dashboard")
include(":feature:cleaner")
include(":feature:duplicates")
include(":feature:compress")
include(":feature:settings")
