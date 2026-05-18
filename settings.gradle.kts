pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useMavenLocalSdk = providers.gradleProperty("useMavenLocalSdk")
        .map(String::toBoolean)
        .orElse(false)

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMavenLocalSdk.get()) {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidGigaSDKLivekit"

include(":gigasdk-livekit")
include(":sample")
