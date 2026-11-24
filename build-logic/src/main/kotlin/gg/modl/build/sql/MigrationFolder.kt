package gg.modl.build.sql

import java.io.File

data class MigrationFolder(val dialect: String, val files: Map<String, String>) {
    companion object {
        fun read(folder: File): MigrationFolder {
            val files = (folder.listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedBy { it.name }
                .associate { it.name to it.readText(Charsets.UTF_8) }
            return MigrationFolder(folder.name, files)
        }
    }
}
