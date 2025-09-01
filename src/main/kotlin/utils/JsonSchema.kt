@file:Suppress("unused")

package moe.tachyon.quiz.utils

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

@Serializable
@OptIn(ExperimentalSerializationApi::class)
sealed interface JsonSchema
{
    typealias Bool = kotlin.Boolean
    typealias DescriptionType = kotlin.String?

    val type: List<JsonSchemaType>
    val description: DescriptionType

    @Serializable
    enum class JsonSchemaType
    {
        @SerialName("object") OBJECT,
        @SerialName("array") ARRAY,
        @SerialName("string") STRING,
        @SerialName("number") NUMBER,
        @SerialName("integer") INTEGER,
        @SerialName("boolean") BOOLEAN,
        @SerialName("null") NULL,
        ;
    }

    private class TypeSerializer: KSerializer<List<JsonSchemaType>>
    {
        private val singleSerializer = JsonSchemaType.serializer()
        private val multipleSerializer = ListSerializer(JsonSchemaType.serializer())

        @OptIn(InternalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("moe.tachyon.utils.JsonSchema.Type", PolymorphicKind.SEALED)
        {
            element("single", singleSerializer.descriptor)
            element("multiple", multipleSerializer.descriptor)
        }

        override fun serialize(encoder: Encoder, value: List<JsonSchemaType>)
        {
            if (value.size == 1)
                encoder.encodeSerializableValue(singleSerializer, value[0])
            else
                encoder.encodeSerializableValue(multipleSerializer, value)
        }

        override fun deserialize(decoder: Decoder): List<JsonSchemaType>
        {
            if (decoder !is JsonDecoder) error("Can be deserialized only by Json")
            val ele = decoder.decodeJsonElement()
            return if (ele is JsonPrimitive) listOf(decoder.json.decodeFromJsonElement(singleSerializer, ele))
            else decoder.json.decodeFromJsonElement(multipleSerializer, ele.jsonArray)
        }
    }

    @ConsistentCopyVisibility
    @Serializable
    data class Object private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val properties: Map<DescriptionType, JsonSchema>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val required: List<DescriptionType>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.OBJECT),
    ): JsonSchema
    {
        constructor(
            properties: Map<DescriptionType, JsonSchema>? = null,
            required: List<DescriptionType>? = null,
            description: DescriptionType = null,
            nullable: Bool = false
        ): this(
            properties = properties,
            required = required,
            description = description,
            type = if (nullable) listOf(JsonSchemaType.OBJECT, JsonSchemaType.NULL) else listOf(JsonSchemaType.OBJECT)
        )
    }

    @ConsistentCopyVisibility
    @Serializable
    data class Array private constructor(
        val items: JsonSchema,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.ARRAY),
    ): JsonSchema
    {
        constructor(items: JsonSchema, description: DescriptionType, nullable: Bool = false): this(
            items = items,
            description = description,
            type = if (nullable) listOf(JsonSchemaType.ARRAY, JsonSchemaType.NULL) else listOf(JsonSchemaType.ARRAY)
        )
    }
    
    @ConsistentCopyVisibility
    @Serializable
    data class String private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.STRING),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val enum: List<kotlin.String>? = null,
    ): JsonSchema
    {
        constructor(description: DescriptionType, enum: List<kotlin.String>? = null, nullable: Bool = false): this(
            description = description,
            type = if (nullable) listOf(JsonSchemaType.STRING, JsonSchemaType.NULL) else listOf(JsonSchemaType.STRING),
            enum = enum
        )
    }
    
    @ConsistentCopyVisibility
    @Serializable
    data class Number private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minimum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maximum: Double? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.NUMBER),
    ): JsonSchema
    {
        constructor(minimum: Double? = null, maximum: Double? = null, description: DescriptionType, nullable: Bool = false): this(
            minimum = minimum,
            maximum = maximum,
            description = description,
            type = if (nullable) listOf(JsonSchemaType.NUMBER, JsonSchemaType.NULL) else listOf(JsonSchemaType.NUMBER)
        )
    }
    
    @ConsistentCopyVisibility
    @Serializable
    data class Integer private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val minimum: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maximum: Long? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.INTEGER),
    ): JsonSchema
    {
        constructor(minimum: Long? = null, maximum: Long? = null, description: DescriptionType, nullable: Bool = false): this(
            minimum = minimum,
            maximum = maximum,
            description = description,
            type = if (nullable) listOf(JsonSchemaType.INTEGER, JsonSchemaType.NULL) else listOf(JsonSchemaType.INTEGER)
        )
    }
    
    @ConsistentCopyVisibility
    @Serializable
    data class Boolean private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.BOOLEAN),
    ): JsonSchema
    {
        constructor(description: DescriptionType, nullable: Bool = false): this(
            description = description,
            type = if (nullable) listOf(JsonSchemaType.BOOLEAN, JsonSchemaType.NULL) else listOf(JsonSchemaType.BOOLEAN)
        )
    }

    @ConsistentCopyVisibility
    @Serializable
    data class Null private constructor(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val description: DescriptionType = null,
    ): JsonSchema
    {
        @Serializable(with = TypeSerializer::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: List<JsonSchemaType> = listOf(JsonSchemaType.NULL)
    }

    @JvmInline
    @Serializable
    value class UnknownJsonSchema(val value: JsonObject): JsonSchema
    {
        override val description: DescriptionType
            get() = value["description"]?.let { if (it is JsonPrimitive) it.content else null }
        override val type: List<JsonSchemaType>
            get() = value["type"]?.let()
            {
                when (it)
                {
                    is JsonPrimitive -> listOf(JsonSchemaType.valueOf(it.content.uppercase()))
                    is JsonObject    -> emptyList()
                    else             -> it.jsonArray.mapNotNull { e -> if (e is JsonPrimitive) JsonSchemaType.valueOf(e.content.uppercase()) else null }
                }
            } ?: emptyList()
    }

    @SerialInfo
    @MustBeDocumented
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Description(val value: kotlin.String)

    @SerialInfo
    @MustBeDocumented
    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class EnumValues(val values: kotlin.Array<kotlin.String>)
}

inline fun <reified T> JsonSchema.Companion.generateSchema(): JsonSchema =
    generateSchema(serializer<T>().descriptor)

@OptIn(ExperimentalSerializationApi::class)
fun  JsonSchema.Companion.generateSchema(descriptor: SerialDescriptor, description: String? = null): JsonSchema
{
    if (descriptor.isInline) return generateSchema(descriptor.getElementDescriptor(0), description)
    return when (descriptor.kind)
    {
        is
        PrimitiveKind.BOOLEAN  -> JsonSchema.Boolean(description, descriptor.isNullable)

        PrimitiveKind.DOUBLE,
        PrimitiveKind.FLOAT    -> JsonSchema.Number(description = description, nullable = descriptor.isNullable)

        PrimitiveKind.BYTE,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.SHORT    -> JsonSchema.Integer(description = description, nullable = descriptor.isNullable)

        SerialKind.ENUM,
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING   ->
        {
            val enums =
                descriptor.annotations.filterIsInstance<JsonSchema.EnumValues>().firstOrNull()?.values?.toList() ?:
                if (descriptor.kind == PrimitiveKind.STRING) null
                else (0..<descriptor.elementsCount).map { descriptor.getElementName(it) }

            JsonSchema.String(description = description, nullable = descriptor.isNullable, enum = enums)
        }

        StructureKind.OBJECT,
        StructureKind.CLASS    -> JsonSchema.Object(
            properties = (0 until descriptor.elementsCount).associate()
            { i ->
                val propDesc = descriptor.getElementDescriptor(i)
                val propName = descriptor.getElementName(i)
                val propDescription = (descriptor.getElementAnnotations(i).filterIsInstance<JsonSchema.Description>().firstOrNull()?.value)
                propName to generateSchema(propDesc, propDescription)
            },
            required = (0 until descriptor.elementsCount).mapNotNull()
            { i ->
                if (descriptor.isElementOptional(i)) null else descriptor.getElementName(i)
            },
            description = description,
            nullable = descriptor.isNullable,
        )

        StructureKind.LIST     -> JsonSchema.Array(
            items = generateSchema(descriptor.getElementDescriptor(0)),
            description = description,
            nullable = descriptor.isNullable,
        )

        StructureKind.MAP,
        SerialKind.CONTEXTUAL,
        PolymorphicKind.OPEN,
        PolymorphicKind.SEALED -> JsonSchema.Object(
            properties = null,
            required = null,
            description = description,
            nullable = descriptor.isNullable,
        )
    }
}