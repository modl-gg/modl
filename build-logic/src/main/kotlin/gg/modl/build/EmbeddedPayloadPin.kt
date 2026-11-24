package gg.modl.build

data class EmbeddedPayloadPin(val version: String, val sha256: String, val size: Long) {
    init {
        require(RELEASE_VERSION.matches(version)) { "modl $version is not a MAJOR.MINOR.PATCH[-prerelease] release version" }
        require(SHA256_HEX.matches(sha256)) { "Invalid SHA-256 for $fileName: $sha256" }
        require(size in 1..MAX_SIZE) { "$fileName has $size bytes; the plugin accepts 1 to $MAX_SIZE" }
    }

    val fileName: String
        get() = "$FILE_PREFIX$version$FILE_SUFFIX"

    fun write(): String = buildString {
        appendLine(HEADER)
        appendLine("$VERSION $version")
        appendLine("$SHA256 $sha256")
        appendLine("$SIZE $size")
    }

    companion object {
        const val FILE_NAME = "payload.txt"
        const val HEADER = "modl-embedded-payload 1"
        private const val VERSION = "version"
        private const val SHA256 = "sha256"
        private const val SIZE = "size"
        private const val FILE_PREFIX = "modl-embedded-"
        private const val FILE_SUFFIX = ".zip"
        private const val MAX_SIZE = 512L * 1024 * 1024
        private val RELEASE_VERSION = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(-[0-9A-Za-z.]{1,32})?")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        fun parse(text: String): EmbeddedPayloadPin {
            val lines = text.lines().filter(String::isNotBlank)
            require(lines.size == 4 && lines[0] == HEADER) { "$FILE_NAME is not a \"$HEADER\" pin" }
            fun field(line: String, key: String): String {
                require(line.startsWith("$key ")) { "Malformed line in $FILE_NAME: $line" }
                return line.removePrefix("$key ")
            }
            val size = requireNotNull(field(lines[3], SIZE).toLongOrNull()) { "Malformed size in $FILE_NAME: ${lines[3]}" }
            return EmbeddedPayloadPin(field(lines[1], VERSION), field(lines[2], SHA256), size)
        }
    }
}
