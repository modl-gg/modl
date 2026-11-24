package gg.modl.build

data class ServerLibraryIndex(val packed: List<PackedLibrary>, val central: List<CentralLibrary>) {
    data class PackedLibrary(val fileName: String, val sha256: String, val size: Long)

    data class CentralLibrary(val coordinates: MavenCoordinates, val sha256: String, val size: Long) {
        val fileName: String
            get() = coordinates.fileName
    }

    init {
        val duplicates = fileNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Embedded server libraries share file names: ${duplicates.sorted().joinToString(", ")}" }
        packed.forEach { require(FILE_NAME.matches(it.fileName)) { "Invalid packed library file name ${it.fileName}" } }
        (packed.map { it.fileName to it.sha256 } + central.map { it.fileName to it.sha256 }).forEach { (fileName, sha256) ->
            require(SHA256_HEX.matches(sha256)) { "Invalid SHA-256 for $fileName: $sha256" }
        }
    }

    val fileNames: List<String>
        get() = packed.map(PackedLibrary::fileName) + central.map(CentralLibrary::fileName)

    fun write(): String = buildString {
        appendLine(HEADER)
        packed.sortedBy(PackedLibrary::fileName).forEach { appendLine(listOf(PACKED, it.sha256, it.size, it.fileName).joinToString(" ")) }
        central.sortedBy { it.coordinates.toString() }
            .forEach { appendLine(listOf(CENTRAL, it.sha256, it.size, it.coordinates).joinToString(" ")) }
    }

    companion object {
        const val FILE_NAME_IN_PAYLOAD = "index.txt"
        const val HEADER = "modl-server-libraries 2"
        private const val PACKED = "packed"
        private const val CENTRAL = "central"
        private const val FIELDS = 4
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar")

        fun parse(text: String): ServerLibraryIndex {
            val lines = text.lines().filter(String::isNotBlank)
            require(lines.firstOrNull() == HEADER) { "$FILE_NAME_IN_PAYLOAD does not start with \"$HEADER\"" }
            val packed = mutableListOf<PackedLibrary>()
            val central = mutableListOf<CentralLibrary>()
            lines.drop(1).forEach { line ->
                val fields = line.split(' ')
                require(fields.size == FIELDS) { "Malformed line in $FILE_NAME_IN_PAYLOAD: $line" }
                val (kind, sha256, size, identity) = fields
                val bytes = requireNotNull(size.toLongOrNull()?.takeIf { it >= 0 }) { "Malformed size in $FILE_NAME_IN_PAYLOAD: $line" }
                when (kind) {
                    PACKED -> packed += PackedLibrary(identity, sha256, bytes)
                    CENTRAL -> central += CentralLibrary(MavenCoordinates.parse(identity), sha256, bytes)
                    else -> throw IllegalArgumentException("Unknown library kind in $FILE_NAME_IN_PAYLOAD: $line")
                }
            }
            return ServerLibraryIndex(packed, central)
        }
    }
}
