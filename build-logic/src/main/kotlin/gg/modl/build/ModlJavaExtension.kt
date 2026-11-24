package gg.modl.build

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

interface ModlJavaExtension {
    val release: Property<Int>
    val usesJdkInternalApis: Property<Boolean>
    val strictness: Property<JavaStrictness>
    val unmarkedPackages: SetProperty<String>

    companion object {
        const val TOOLCHAIN_VERSION = 21
        const val MINECRAFT_RUNTIME_RELEASE = 8
        const val NULL_MARKED_PACKAGES = "gg.modl"
        val DISABLED_BUG_CHECKS = listOf(
            "EmptyCatch",
            "ImmutableEnumChecker",
            "ReferenceEquality",
            "StringSplitter",
        )
    }
}
