pluginManagement {
    repositories {
        google()
        // Prefer the Google-hosted Maven Central mirror to avoid transient 429s
        // from repo.maven.apache.org on clean GitHub Actions runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Prefer the Google-hosted Maven Central mirror to avoid transient 429s
        // from repo.maven.apache.org on clean GitHub Actions runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "MolidoMeshAI"
include(":app")


