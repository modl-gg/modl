package gg.modl.build.sql

object MigrationParity {
    private val FILE_NAME = Regex("V([1-9][0-9]*)__[a-z0-9_]+\\.sql")
    private val COMMENT_MARKERS = listOf("--", "/*", "*/")
    private const val EM_DASH = '\u2014'

    fun problems(folders: List<MigrationFolder>): List<String> {
        if (folders.isEmpty()) {
            return listOf("no dialect folder was given")
        }
        return folders.flatMap { fileProblems(it) } + parityProblems(folders)
    }

    private fun fileProblems(folder: MigrationFolder): List<String> {
        if (folder.files.isEmpty()) {
            return listOf("${folder.dialect}: holds no migration")
        }
        return folder.files.flatMap { (name, text) -> fileProblems(folder.dialect, name, text) } +
            duplicateVersions(folder)
    }

    private fun fileProblems(dialect: String, name: String, text: String): List<String> {
        val problems = mutableListOf<String>()
        if (!FILE_NAME.matches(name)) {
            problems += "$dialect/$name: the name must match V<version>__<lower_snake_description>.sql"
        }
        text.lineSequence().forEachIndexed { index, line ->
            val location = "$dialect/$name:${index + 1}"
            if (COMMENT_MARKERS.any { line.contains(it) } || line.trimStart().startsWith("#")) {
                problems += "$location: migrations carry no comments"
            }
            if (line.contains(EM_DASH)) {
                problems += "$location: migrations carry no em-dashes"
            }
        }
        if (text.isBlank()) {
            problems += "$dialect/$name: is empty"
        }
        return problems
    }

    private fun duplicateVersions(folder: MigrationFolder): List<String> =
        folder.files.keys.mapNotNull { version(it) }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys.sorted()
            .map { "${folder.dialect}: version $it is defined more than once" }

    private fun parityProblems(folders: List<MigrationFolder>): List<String> {
        val namesByDialect = folders.associate { folder -> folder.dialect to folder.files.keys.filter { FILE_NAME.matches(it) }.toSet() }
        val everyName = namesByDialect.values.flatten().toSortedSet()
        return everyName.flatMap { name ->
            namesByDialect.filterValues { name !in it }.keys.sorted().map { dialect -> "$dialect: $name is missing" }
        }
    }

    private fun version(name: String): Int? = FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.toInt()
}
