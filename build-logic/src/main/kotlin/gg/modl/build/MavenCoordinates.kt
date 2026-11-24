package gg.modl.build

import java.io.Serializable

data class MavenCoordinates(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
) : Serializable {
    init {
        require(listOfNotNull(group, artifact, version, classifier).all(PART::matches)) {
            "Invalid Maven coordinates ${parts().joinToString(SEPARATOR)}"
        }
    }

    val fileName: String
        get() = "$artifact-$version${classifier?.let { "-$it" }.orEmpty()}$JAR_SUFFIX"

    override fun toString(): String = parts().joinToString(SEPARATOR)

    private fun parts(): List<String> = listOfNotNull(group, artifact, version, classifier)

    companion object {
        private const val SEPARATOR = ":"
        private const val JAR_SUFFIX = ".jar"
        private val PART = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

        fun parse(text: String): MavenCoordinates {
            val parts = text.split(SEPARATOR)
            require(parts.size == 3 || parts.size == 4) { "Invalid Maven coordinates $text" }
            return MavenCoordinates(parts[0], parts[1], parts[2], parts.getOrNull(3))
        }

        fun ofFile(group: String, artifact: String, version: String, fileName: String): MavenCoordinates {
            val stem = "$artifact-$version"
            require(fileName.endsWith(JAR_SUFFIX)) {
                "$group:$artifact:$version resolves to $fileName, but the plugin can only download jars"
            }
            val name = fileName.removeSuffix(JAR_SUFFIX)
            return when {
                name == stem -> MavenCoordinates(group, artifact, version)
                name.startsWith("$stem-") -> MavenCoordinates(group, artifact, version, name.removePrefix("$stem-"))
                else -> throw IllegalArgumentException(
                    "$group:$artifact:$version resolves to $fileName, which is not a Maven repository file name of it"
                )
            }
        }
    }
}
