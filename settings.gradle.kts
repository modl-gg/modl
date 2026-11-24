pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        val mavenCentral = mavenCentral()
        val spigotmc = maven("https://hub.spigotmc.org/nexus/content/groups/public/") {
            name = "spigotmc"
        }
        val papermc = maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        exclusiveContent {
            forRepositories(spigotmc)
            filter {
                includeGroup("org.spigotmc")
                includeGroup("org.bukkit")
            }
        }
        exclusiveContent {
            forRepositories(papermc)
            filter {
                includeGroup("com.velocitypowered")
                includeGroupAndSubgroups("io.papermc")
            }
        }
        exclusiveContent {
            forRepositories(spigotmc, papermc)
            filter {
                includeGroup("net.md-5")
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://repo.codemc.io/repository/maven-releases/") {
                    name = "codemc"
                    mavenContent {
                        releasesOnly()
                    }
                }
            }
            filter {
                includeGroup("com.github.retrooper")
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://libraries.minecraft.net/") {
                    name = "minecraft"
                    mavenContent {
                        releasesOnly()
                    }
                }
            }
            filter {
                includeGroup("com.mojang")
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://repo.grim.ac/snapshots") {
                    name = "grim"
                }
            }
            filter {
                includeGroup("ac.grim.grimac")
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://repo.polar.top/repository/polar/") {
                    name = "polar"
                }
            }
            filter {
                includeGroup("top.polar")
            }
        }
        matching { it != mavenCentral }.configureEach {
            content {
                notForConfigurations("embeddedRuntimeClasspath")
            }
        }
    }
}

rootProject.name = "modl"

include(":proto")

include(":backend")

include(":minecraft:api")
include(":minecraft:core")
include(":minecraft:server-features")
include(":minecraft:platforms:spigot")
include(":minecraft:platforms:spigot-sv")
include(":minecraft:platforms:vulcan-api-stub")
include(":minecraft:platforms:velocity")
include(":minecraft:platforms:bungee")
include(":minecraft:distribution")

include(":libs:address-scope")
include(":libs:update-protocol")
include(":libs:replay-format")
include(":libs:replay-recording")
listOf("api", "common", "spigot", "bungeecord", "velocity").forEach { module ->
    include(":libs:cirrus:cirrus-$module")
}
listOf("core", "bukkit", "bungee", "velocity").forEach { module ->
    include(":libs:libby:libby-$module")
    project(":libs:libby:libby-$module").projectDir = file("libs/libby/$module")
}
