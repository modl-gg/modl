package gg.modl.build

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.named

object ModlArtifacts {
    val TYPE: Attribute<String> = Attribute.of("modl.artifact", String::class.java)
    const val CATEGORY = "modl-artifact"
    const val EMBEDDED_SERVER = "embedded-server"
    const val RELEASE_JAR = "release-jar"
}

fun AttributeContainer.modlArtifact(objects: ObjectFactory, type: String) {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(ModlArtifacts.CATEGORY))
    attribute(ModlArtifacts.TYPE, type)
}
