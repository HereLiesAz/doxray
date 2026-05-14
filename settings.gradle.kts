pluginManagement {
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
        maven {
            url = uri("https://maven.pkg.github.com/OWNER/REPO") // Placeholder for actual repo if needed
            credentials {
                username = System.getenv("GH_USER") ?: "token"
                password = System.getenv("GH_TOKEN")
            }
        }
    }
}
rootProject.name = "Doxray"
include(":app")