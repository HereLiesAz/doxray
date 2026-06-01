import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProps = Properties().apply {
    val f = rootDir.resolve("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase())

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        // Optional: closed-beta GitHub Packages repo for the Meta Wearables DAT SDK.
        // Provide gh.packages.url, gh.user, gh.token in local.properties (or as env vars).
        val ghUrl = prop("gh.packages.url")
        if (!ghUrl.isNullOrBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri(ghUrl)
                credentials {
                    username = prop("GH_ACTOR") ?: "token"
                    password = prop("GH_TOKEN") ?: ""
                }
            }
        }
    }
}

rootProject.name = "Doxray"
include(":app")
