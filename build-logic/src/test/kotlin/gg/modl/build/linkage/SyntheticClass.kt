package gg.modl.build.linkage

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes

class SyntheticClass(private val name: String, private val javaVersion: Int = 8) {
    private var superName = OBJECT
    private val declaredMethods = mutableListOf<Member>()
    private val calls = mutableListOf<MemberReference>()
    private val classNameConstants = mutableListOf<String>()
    private val methodHandles = mutableListOf<MemberReference>()

    fun extending(superName: String) = apply { this.superName = superName }

    fun declaring(name: String, descriptor: String) = apply { declaredMethods += Member(name, descriptor) }

    fun calling(owner: String, name: String, descriptor: String) = apply {
        calls += MemberReference(owner, Member(name, descriptor))
    }

    fun loadingByName(binaryName: String) = apply { classNameConstants += binaryName }

    fun referencingMethod(owner: String, name: String, descriptor: String) = apply {
        methodHandles += MemberReference(owner, Member(name, descriptor))
    }

    fun toClassFile(): ClassFile = ClassFile.parse(bytes())

    private fun bytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            ClassFile.majorVersionOf(javaVersion),
            Opcodes.ACC_PUBLIC,
            name,
            null,
            superName,
            null,
        )
        declaredMethods.forEach { method ->
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, method.name, method.descriptor, null, null).visitEnd()
        }
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, BODY_METHOD, VOID_DESCRIPTOR, null, null)
        body.visitCode()
        calls.forEach { call ->
            body.visitMethodInsn(Opcodes.INVOKESTATIC, call.owner, call.member.name, call.member.descriptor, false)
        }
        classNameConstants.forEach { constant ->
            body.visitLdcInsn(constant)
            body.visitInsn(Opcodes.POP)
        }
        methodHandles.forEach { handle ->
            body.visitLdcInsn(Handle(Opcodes.H_INVOKESTATIC, handle.owner, handle.member.name, handle.member.descriptor, false))
            body.visitInsn(Opcodes.POP)
        }
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(MAX_STACK, 0)
        body.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        const val OBJECT = "java/lang/Object"
        const val BODY_METHOD = "run"
        const val VOID_DESCRIPTOR = "()V"
        const val MAX_STACK = 8
    }
}
