package gg.modl.build

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

class JarContentsTest {
    @TempDir
    lateinit var folder: File

    @Test
    fun aStarterWithOnlyAManifestAndLegalTextsHasNoContent() {
        val starter = jar(
            "META-INF/", "META-INF/MANIFEST.MF", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt",
            "META-INF/maven/org.example/starter/pom.xml",
        )

        assertTrue(JarContents.isMetadataOnly(starter))
    }

    @Test
    fun aJarWithClassesOrResourcesHasContent() {
        assertFalse(JarContents.isMetadataOnly(jar("META-INF/MANIFEST.MF", "org/example/Library.class")))
        assertFalse(JarContents.isMetadataOnly(jar("META-INF/MANIFEST.MF", "schema.json")))
    }

    @Test
    fun metaInfFilesThatShapeTheClassPathAreContent() {
        assertFalse(JarContents.isMetadataOnly(jar("META-INF/MANIFEST.MF", "META-INF/services/org.example.Provider")))
        assertFalse(JarContents.isMetadataOnly(jar("META-INF/MANIFEST.MF", "META-INF/spring.factories")))
        assertFalse(JarContents.isMetadataOnly(jar("META-INF/MANIFEST.MF", "META-INF/versions/21/org/example/Library.class")))
    }

    @Test
    fun aJarWithNoEntriesHasNoContent() {
        assertTrue(JarContents.isMetadataOnly(jar()))
    }

    @Test
    fun aManifestThatExtendsTheClassPathIsContent() {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = "other-library.jar"
        }

        assertFalse(JarContents.isMetadataOnly(jar(manifest)))
    }

    private fun jar(vararg entries: String): File = jar(null, *entries)

    private fun jar(manifest: Manifest?, vararg entries: String): File {
        val file = File(folder, "library-${folder.list()!!.size}.jar")
        val stream = file.outputStream()
        (if (manifest == null) JarOutputStream(stream) else JarOutputStream(stream, manifest)).use { output ->
            entries.forEach { name ->
                output.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) output.write(name.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
