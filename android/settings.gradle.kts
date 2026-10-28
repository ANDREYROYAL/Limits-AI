pluginManagement {
    repositories {
        if (System.getenv("CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CORE_ONLY") == null) google()
        mavenCentral()
    }
}

rootProject.name = "LimitsAI"
include(":core")
// CORE_ONLY=1 позволяет собрать и протестировать :core без Android SDK.
if (System.getenv("CORE_ONLY") == null) include(":app")
