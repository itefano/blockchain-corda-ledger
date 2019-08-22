package net.corda.serialization.internal.amqp

import net.corda.core.DeleteForDJVM
import net.corda.core.KeepForDJVM
import net.corda.core.serialization.ClassWhitelist
import net.corda.serialization.internal.carpenter.ClassCarpenter
import net.corda.serialization.internal.carpenter.ClassCarpenterImpl
import net.corda.serialization.internal.model.*
import java.io.NotSerializableException
import java.util.Collections.unmodifiableMap
import java.util.function.Function

@KeepForDJVM
object SerializerFactoryBuilder {
    /**
     * The standard mapping of Java object types to Java primitive types.
     * The DJVM will need to override these, but probably not anyone else.
     */
    @Suppress("unchecked_cast")
    private val javaPrimitiveTypes: Map<Class<*>, Class<*>> = unmodifiableMap(mapOf<Class<out Any>?, Class<out Any>?>(
        Boolean::class.javaObjectType to Boolean::class.javaPrimitiveType,
        Byte::class.javaObjectType to Byte::class.javaPrimitiveType,
        Char::class.javaObjectType to Char::class.javaPrimitiveType,
        Double::class.javaObjectType to Double::class.javaPrimitiveType,
        Float::class.javaObjectType to Float::class.javaPrimitiveType,
        Int::class.javaObjectType to Int::class.javaPrimitiveType,
        Long::class.javaObjectType to Long::class.javaPrimitiveType,
        Short::class.javaObjectType to Short::class.javaPrimitiveType
    )) as Map<Class<*>, Class<*>>

    @JvmStatic
    fun build(whitelist: ClassWhitelist, classCarpenter: ClassCarpenter): SerializerFactory {
        return makeFactory(
                whitelist,
                classCarpenter,
                DefaultDescriptorBasedSerializerRegistry(),
                allowEvolution = true,
                overrideFingerPrinter = null,
                onlyCustomSerializers = false,
                mustPreserveDataWhenEvolving = false)
    }

    @JvmStatic
    @DeleteForDJVM
    fun build(
            whitelist: ClassWhitelist,
            classCarpenter: ClassCarpenter,
            descriptorBasedSerializerRegistry: DescriptorBasedSerializerRegistry =
                    DefaultDescriptorBasedSerializerRegistry(),
            allowEvolution: Boolean = true,
            overrideFingerPrinter: FingerPrinter? = null,
            onlyCustomSerializers: Boolean = false,
            mustPreserveDataWhenEvolving: Boolean = false): SerializerFactory {
        return makeFactory(
                whitelist,
                classCarpenter,
                descriptorBasedSerializerRegistry,
                allowEvolution,
                overrideFingerPrinter,
                onlyCustomSerializers,
                mustPreserveDataWhenEvolving)
    }

    @JvmStatic
    @DeleteForDJVM
    fun build(
            whitelist: ClassWhitelist,
            carpenterClassLoader: ClassLoader,
            lenientCarpenterEnabled: Boolean = false,
            descriptorBasedSerializerRegistry: DescriptorBasedSerializerRegistry =
                    DefaultDescriptorBasedSerializerRegistry(),
            allowEvolution: Boolean = true,
            overrideFingerPrinter: FingerPrinter? = null,
            onlyCustomSerializers: Boolean = false,
            mustPreserveDataWhenEvolving: Boolean = false): SerializerFactory {
        return makeFactory(
                whitelist,
                ClassCarpenterImpl(whitelist, carpenterClassLoader, lenientCarpenterEnabled),
                descriptorBasedSerializerRegistry,
                allowEvolution,
                overrideFingerPrinter,
                onlyCustomSerializers,
                mustPreserveDataWhenEvolving)
    }

    private fun makeFactory(whitelist: ClassWhitelist,
                            classCarpenter: ClassCarpenter,
                            descriptorBasedSerializerRegistry: DescriptorBasedSerializerRegistry,
                            allowEvolution: Boolean,
                            overrideFingerPrinter: FingerPrinter?,
                            onlyCustomSerializers: Boolean,
                            mustPreserveDataWhenEvolving: Boolean): SerializerFactory {
        val customSerializerRegistry = CachingCustomSerializerRegistry(descriptorBasedSerializerRegistry)

        val localTypeModel = ConfigurableLocalTypeModel(
                WhitelistBasedTypeModelConfiguration(
                        whitelist,
                        customSerializerRegistry))

        val fingerPrinter = overrideFingerPrinter ?:
            TypeModellingFingerPrinter(customSerializerRegistry)

        val localSerializerFactory = DefaultLocalSerializerFactory(
                whitelist,
                localTypeModel,
                fingerPrinter,
                classCarpenter.classloader,
                descriptorBasedSerializerRegistry,
                Function { clazz -> AMQPPrimitiveSerializer(clazz) },
                customSerializerRegistry,
                onlyCustomSerializers)

        val typeLoader: TypeLoader = ClassCarpentingTypeLoader(
                SchemaBuildingRemoteTypeCarpenter(classCarpenter),
                classCarpenter.classloader)

        val evolutionSerializerFactory = if (allowEvolution) DefaultEvolutionSerializerFactory(
                localSerializerFactory,
                classCarpenter.classloader,
                mustPreserveDataWhenEvolving,
                javaPrimitiveTypes
        ) else NoEvolutionSerializerFactory

        val remoteSerializerFactory = DefaultRemoteSerializerFactory(
                evolutionSerializerFactory,
                descriptorBasedSerializerRegistry,
                AMQPRemoteTypeModel(),
                localTypeModel,
                typeLoader,
                localSerializerFactory)

        return ComposedSerializerFactory(localSerializerFactory, remoteSerializerFactory, customSerializerRegistry)
    }

}

object NoEvolutionSerializerFactory : EvolutionSerializerFactory {
    override fun getEvolutionSerializer(remote: RemoteTypeInformation, local: LocalTypeInformation): AMQPSerializer<Any> {
        throw NotSerializableException("""
Evolution not permitted.

Remote:
${remote.prettyPrint(false)}

Local:
${local.prettyPrint(false)}
        """)
    }

    override val primitiveTypes: Map<Class<*>, Class<*>> = emptyMap()
}