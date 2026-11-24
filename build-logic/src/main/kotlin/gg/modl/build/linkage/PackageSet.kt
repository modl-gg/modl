package gg.modl.build.linkage

class PackageSet(packageNames: Collection<String>) {
    private val prefixes = packageNames.map { it.replace('.', '/') + "/" }

    operator fun contains(internalName: String): Boolean = prefixes.any(internalName::startsWith)
}
