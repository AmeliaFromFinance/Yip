pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
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

rootProject.name = "Yip"

include(":app")
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:playback")
include(":core:designsystem")
include(":feature:browse")
include(":feature:watch")
include(":feature:settings")
