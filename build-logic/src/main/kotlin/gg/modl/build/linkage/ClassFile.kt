package gg.modl.build.linkage

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

data class Member(val name: String, val descriptor: String) {
    val isConstructor: Boolean
        get() = name == CONSTRUCTOR_NAME

    override fun toString(): String = "$name$descriptor"

    private companion object {
        const val CONSTRUCTOR_NAME = "<init>"
    }
}

data class MemberReference(val owner: String, val member: Member) {
    override fun toString(): String = "$owner.$member"
}

class ClassFile(
    val name: String,
    val majorVersion: Int,
    val superName: String?,
    val interfaces: List<String>,
    val declaredMembers: Set<Member>,
    val referencedClasses: Set<String>,
    val memberReferences: Set<MemberReference>,
    val stringConstants: Set<String>,
) {
    val supertypes: List<String>
        get() = listOfNotNull(superName) + interfaces

    val javaVersion: Int
        get() = javaVersionOf(majorVersion)

    companion object {
        private const val JAVA_1_0_MAJOR_VERSION_OFFSET = 44
        private const val MAJOR_VERSION_MASK = 0xFFFF

        fun javaVersionOf(majorVersion: Int): Int = majorVersion - JAVA_1_0_MAJOR_VERSION_OFFSET

        fun majorVersionOf(javaVersion: Int): Int = javaVersion + JAVA_1_0_MAJOR_VERSION_OFFSET

        fun parse(bytes: ByteArray): ClassFile {
            val collector = Collector()
            ClassReader(bytes).accept(ClassRemapper(collector, collector.types), ClassReader.SKIP_DEBUG)
            return collector.toClassFile()
        }

        fun parseIfSupported(bytes: ByteArray): ClassFile? =
            try {
                parse(bytes)
            } catch (unsupportedClassFileVersion: IllegalArgumentException) {
                null
            }
    }

    private class Collector : ClassVisitor(Opcodes.ASM9) {
        val types = TypeRecorder()
        private val declaredMembers = linkedSetOf<Member>()
        private val memberReferences = linkedSetOf<MemberReference>()
        private val stringConstants = linkedSetOf<String>()
        private var name = ""
        private var majorVersion = 0
        private var superName: String? = null
        private var interfaces = emptyList<String>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            this.name = name
            this.majorVersion = version and MAJOR_VERSION_MASK
            this.superName = superName
            this.interfaces = interfaces?.toList().orEmpty()
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            declaredMembers += Member(name, descriptor)
            if (value is String) stringConstants += value
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            declaredMembers += Member(name, descriptor)
            return References()
        }

        fun toClassFile(): ClassFile = ClassFile(
            name = name,
            majorVersion = majorVersion,
            superName = superName,
            interfaces = interfaces,
            declaredMembers = declaredMembers,
            referencedClasses = types.names - name,
            memberReferences = memberReferences,
            stringConstants = stringConstants,
        )

        private inner class References : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                reference(owner, name, descriptor)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                reference(owner, name, descriptor)
            }

            override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                reference(bootstrap)
                arguments.filterIsInstance<Handle>().forEach(::reference)
            }

            override fun visitLdcInsn(value: Any?) {
                when (value) {
                    is String -> stringConstants += value
                    is Handle -> reference(value)
                }
            }

            private fun reference(handle: Handle) {
                reference(handle.owner, handle.name, handle.desc)
            }

            private fun reference(owner: String, name: String, descriptor: String) {
                memberReferences += MemberReference(owner, Member(name, descriptor))
            }
        }
    }

    private class TypeRecorder : Remapper(Opcodes.ASM9) {
        val names = linkedSetOf<String>()

        override fun map(internalName: String): String {
            names += internalName
            return internalName
        }
    }
}
