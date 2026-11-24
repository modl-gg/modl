package gg.modl.build.linkage

class PlatformLinkage(
    private val label: String,
    private val plugin: ClassIndex,
    private val entryPoint: String,
    private val javaVersion: Int,
    private val runtimeGates: Set<String>,
    private val serverLibraries: ServerLibraries,
    private val pluginPackages: PackageSet,
    private val runtimeLoadedPackages: PackageSet,
) {
    private val graph = ClassGraph(plugin, runtimeGates)

    fun verify(): LinkageResult {
        val entryClass = plugin[entryPoint]
            ?: return LinkageResult(listOf("$label: entry point $entryPoint is not in the plugin jar"), "$label: no entry point")
        val reachable = graph.reachableFrom(entryPoint)
        val gated = runtimeGates.flatMap(graph::gatedEntryPointsOf).toSortedSet().map(graph::reachableFrom)
        val problems = gateProblems(reachable) +
            bytecodeProblems(reachable, javaVersion, "$label runs on Java $javaVersion") +
            gated.flatMap(::gatedBytecodeProblems) +
            (listOf(reachable) + gated).flatMap(::missingPluginClassProblems) +
            serverLibraryProblems(reachable)
        return LinkageResult(problems, summary(entryClass, reachable, gated))
    }

    private fun gateProblems(reachable: Reachability): List<String> = runtimeGates.sorted().mapNotNull { gate ->
        when {
            gate !in plugin -> "$label: runtime gate $gate is not in the plugin jar"
            gate !in reachable.classes -> "$label: runtime gate $gate is not reachable from $entryPoint"
            graph.gatedEntryPointsOf(gate).isEmpty() -> "$label: runtime gate $gate loads no plugin class by name"
            else -> null
        }
    }

    private fun gatedBytecodeProblems(gated: Reachability): List<String> {
        val root = plugin[gated.root] ?: return emptyList()
        return bytecodeProblems(gated, root.javaVersion, "runtime-gated ${root.name} is Java ${root.javaVersion}")
    }

    private fun bytecodeProblems(reachable: Reachability, ceiling: Int, promise: String): List<String> =
        classesOf(reachable).filter { it.javaVersion > ceiling }.map { newer ->
            "$label: ${newer.name} is Java ${newer.javaVersion} bytecode, but $promise and reaches it without a runtime " +
                "gate: ${path(reachable, newer)}"
        }

    private fun missingPluginClassProblems(reachable: Reachability): List<String> = classesOf(reachable).flatMap { classFile ->
        classFile.referencedClasses
            .filter { it in pluginPackages && it !in plugin && it !in runtimeLoadedPackages }
            .sorted()
            .map { missing ->
                "$label: ${classFile.name} uses class $missing, which is neither in the plugin jar nor in a library " +
                    "loaded at runtime, reached through ${path(reachable, classFile)}"
            }
    }

    private fun serverLibraryProblems(reachable: Reachability): List<String> = classesOf(reachable).flatMap { classFile ->
        val missingClasses = serverLibraries.missingClassesUsedBy(classFile).sorted().map { "class $it" }
        val missingMembers = serverLibraries.missingMembersUsedBy(classFile).map(MemberReference::toString).sorted()
        (missingClasses + missingMembers).map { missing ->
            "$label: ${classFile.name} uses $missing, which the server libraries (${serverLibraries.description}) " +
                "do not provide, reached through ${path(reachable, classFile)}"
        }
    }

    private fun summary(entryClass: ClassFile, reachable: Reachability, gated: List<Reachability>): String {
        val checkedReferences = classesOf(reachable).sumOf(serverLibraries::referencesUsedBy)
        val gatedSummary = gated.mapNotNull { gatedReachable ->
            plugin[gatedReachable.root]?.let { root ->
                ", runtime-gated ${root.name} (Java ${root.javaVersion}, ${gatedReachable.classes.size} classes)"
            }
        }.joinToString("")
        return "$label: ${entryClass.name} (Java $javaVersion, ${reachable.classes.size} classes)$gatedSummary, " +
            "$checkedReferences references into server libraries (${serverLibraries.description}) checked"
    }

    private fun classesOf(reachable: Reachability): List<ClassFile> =
        reachable.classes.sorted().mapNotNull(plugin::get)

    private fun path(reachable: Reachability, classFile: ClassFile): String =
        reachable.pathTo(classFile.name).joinToString(" -> ")
}

class LinkageResult(val problems: List<String>, val summary: String)
