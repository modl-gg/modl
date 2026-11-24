package gg.modl.build

import java.io.Serializable

data class PackageRelocation(val pattern: String, val destination: String, val providedBy: String) : Serializable

object PluginRelocations {
    val PROTOBUF = PackageRelocation("com.google.protobuf", "gg.modl.libs.protobuf", providedBy = "PROTOBUF_JAVA")
    val GSON = PackageRelocation("com.google.gson", "gg.modl.libs.gson", providedBy = "GSON")
    val BUF_VALIDATE = PackageRelocation("com.buf.validate", "gg.modl.libs.bufvalidate", providedBy = "PROTO")

    val ALL = listOf(PROTOBUF, GSON, BUF_VALIDATE)

    val RELOCATED_LIBRARIES: Set<String> = ALL.map(PackageRelocation::providedBy).toSet()

    fun relocates(libraryConstant: String): Boolean = libraryConstant in RELOCATED_LIBRARIES
}
