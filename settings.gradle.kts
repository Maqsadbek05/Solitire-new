pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SolitaireMoveHelper"
include(":app")
