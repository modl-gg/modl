pluginManagement {
    includeBuild("../../../build-logic")
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
                    name = "spigotmc"
                }
            }
            filter {
                includeGroup("org.spigotmc")
                includeGroup("org.bukkit")
                includeGroup("net.md-5")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "litebans-fixture"
