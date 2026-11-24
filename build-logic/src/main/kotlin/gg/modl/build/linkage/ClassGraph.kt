package gg.modl.build.linkage

class Reachability internal constructor(val root: String, private val predecessors: Map<String, String?>) {
    val classes: Set<String>
        get() = predecessors.keys

    fun pathTo(name: String): List<String> = generateSequence(name) { predecessors[it] }.toList().asReversed()
}

class ClassGraph(private val classes: ClassIndex, private val runtimeGates: Set<String>) {
    fun reachableFrom(root: String): Reachability {
        val predecessors = linkedMapOf<String, String?>(root to null)
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            successorsOf(current).filter { it !in predecessors }.forEach { next ->
                predecessors[next] = current
                pending.addLast(next)
            }
        }
        return Reachability(root, predecessors)
    }

    fun gatedEntryPointsOf(gate: String): Set<String> = classes[gate]?.let(::classesNamedBy).orEmpty().toSet()

    private fun successorsOf(name: String): List<String> {
        val classFile = classes[name] ?: return emptyList()
        val loadedByName = if (name in runtimeGates) emptySequence() else classesNamedBy(classFile)
        return (classFile.referencedClasses.asSequence() + loadedByName).filter { it in classes }.distinct().toList()
    }

    private fun classesNamedBy(classFile: ClassFile): Sequence<String> =
        classFile.stringConstants.asSequence()
            .map { it.replace(BINARY_NAME_SEPARATOR, INTERNAL_NAME_SEPARATOR) }
            .filter { it in classes && it != classFile.name }

    private companion object {
        const val BINARY_NAME_SEPARATOR = '.'
        const val INTERNAL_NAME_SEPARATOR = '/'
    }
}
