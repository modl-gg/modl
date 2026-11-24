package gg.modl.build

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile

object JarContents {
    private const val META_INF = "META-INF/"
    private const val MANIFEST = "MANIFEST.MF"
    private const val MAVEN_METADATA = "maven/"
    private val LEGAL_TEXT = Regex("(?i)(?:LICENSE|NOTICE)[^/]*")

    fun isMetadataOnly(jar: File): Boolean = JarFile(jar).use { archive ->
        archive.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH) == null &&
            archive.entries().asSequence().filterNot { it.isDirectory }.all { isJarMetadata(it.name) }
    }

    private fun isJarMetadata(entry: String): Boolean {
        if (!entry.startsWith(META_INF)) return false
        val name = entry.removePrefix(META_INF)
        return name == MANIFEST || name.startsWith(MAVEN_METADATA) || LEGAL_TEXT.matches(name)
    }
}
