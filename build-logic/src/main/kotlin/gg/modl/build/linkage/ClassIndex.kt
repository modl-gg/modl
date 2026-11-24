package gg.modl.build.linkage

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ClassIndex(classFiles: Iterable<ClassFile>) {
    private val classes = classFiles.associateBy { it.name }

    val names: Set<String>
        get() = classes.keys

    operator fun get(name: String): ClassFile? = classes[name]

    operator fun contains(name: String): Boolean = name in classes

    fun without(packages: PackageSet): ClassIndex = ClassIndex(classes.values.filterNot { it.name in packages })

    companion object {
        private const val CLASS_SUFFIX = ".class"
        private const val METADATA_DIRECTORY = "META-INF/"
        private const val MODULE_DESCRIPTOR = "module-info.class"

        fun read(jars: Iterable<File>): ClassIndex = ClassIndex(jars.flatMap(::readJar))

        private fun readJar(jar: File): List<ClassFile> = ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter(::isClassOnTheClassPath)
                .map { entry -> zip.getInputStream(entry).use { ClassFile.parse(it.readBytes()) } }
                .toList()
        }

        private fun isClassOnTheClassPath(entry: ZipEntry): Boolean =
            !entry.isDirectory &&
                entry.name.endsWith(CLASS_SUFFIX) &&
                !entry.name.startsWith(METADATA_DIRECTORY) &&
                !entry.name.endsWith(MODULE_DESCRIPTOR)
    }
}
