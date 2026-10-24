// Optional per-machine override (e.g. in ~/.gradle/gradle.properties) for networks where Maven Central is unreliable.
val mavenCentralMirror: String? = providers.gradleProperty("mavenCentralMirror").orNull

pluginManagement {
    val mirror: String? = providers.gradleProperty("mavenCentralMirror").orNull
    repositories {
        if (mirror != null) maven(mirror)
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.(android|testing).*")
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
        if (mavenCentralMirror != null) maven(mavenCentralMirror)
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.(android|testing).*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "SmartManagerInstallments"

include(":core")
// `-PcoreOnly` builds only the pure-Kotlin business-logic module (no Android SDK required).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
