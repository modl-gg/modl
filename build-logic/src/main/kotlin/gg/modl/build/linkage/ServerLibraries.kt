package gg.modl.build.linkage

class ServerLibraries(private val libraries: ClassIndex, private val packages: PackageSet, val description: String) {
    private val jdk = JdkClasses()

    fun missingClassesUsedBy(classFile: ClassFile): List<String> =
        classFile.referencedClasses.filter { provides(it) && it !in libraries }

    fun missingMembersUsedBy(classFile: ClassFile): List<MemberReference> =
        classFile.memberReferences.filter { provides(it.owner) && it.owner in libraries && !resolves(it) }

    fun referencesUsedBy(classFile: ClassFile): Int = classFile.memberReferences.count { provides(it.owner) }

    private fun provides(name: String): Boolean = name in packages

    private fun resolves(reference: MemberReference): Boolean {
        val owner = libraries[reference.owner] ?: return false
        if (reference.member.isConstructor) return reference.member in owner.declaredMembers
        return declaresOrInherits(owner, reference.member, hashSetOf())
    }

    private fun declaresOrInherits(type: ClassFile, member: Member, visited: MutableSet<String>): Boolean {
        if (!visited.add(type.name)) return false
        if (member in type.declaredMembers) return true
        for (supertype in type.supertypes) {
            val supertypeFile = libraries[supertype] ?: jdk[supertype] ?: return UNINSPECTABLE_SUPERTYPE_MAY_DECLARE_IT
            if (declaresOrInherits(supertypeFile, member, visited)) return true
        }
        return false
    }

    private class JdkClasses {
        private val loaded = hashMapOf<String, ClassFile?>()

        operator fun get(name: String): ClassFile? {
            if (!name.startsWith(JDK_PACKAGE_PREFIX)) return null
            return loaded.getOrPut(name) { load(name) }
        }

        private fun load(name: String): ClassFile? =
            ClassLoader.getPlatformClassLoader().getResourceAsStream("$name.class")?.use { ClassFile.parseIfSupported(it.readBytes()) }

        private companion object {
            const val JDK_PACKAGE_PREFIX = "java/"
        }
    }

    private companion object {
        const val UNINSPECTABLE_SUPERTYPE_MAY_DECLARE_IT = true
    }
}
