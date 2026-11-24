import gg.modl.build.VerifyVersionLine

plugins {
    id("modl.cirrus-module")
}

dependencies {
    api(project(":libs:cirrus:cirrus-common"))
    compileOnly(libs.spigot.modern.api)
    compileOnly(libs.packetevents.spigot)
    compileOnly(libs.adventure.api)
}

val verifySpigotModernApiLine = tasks.register<VerifyVersionLine>("verifySpigotModernApiLine") {
    description = "Fails unless spigot-modern stays on Spigot 1.20, the newest API before Bukkit turned classes and enums into interfaces"
    catalogVersion = "spigot-modern"
    pinnedVersion = libs.versions.spigot.modern
    requiredLine = "1.20."
    reason = "cirrus-spigot is shaded into a plugin that runs on Spigot 1.8.8 and newer, so it must compile against " +
        "the newest Spigot API older than 1.21. From 1.21 Bukkit declares InventoryView, PatternType, " +
        "Villager.Profession and other types as interfaces (Sound from 1.21.3), and code compiled against them fails " +
        "with IncompatibleClassChangeError on older servers."
}

tasks.jar {
    dependsOn(verifySpigotModernApiLine)
}

tasks.check {
    dependsOn(verifySpigotModernApiLine)
}
