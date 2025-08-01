@file:Suppress("unused")
package moe.tachyon.quiz.utils.ai

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import moe.tachyon.quiz.utils.ai.tools.AiTools
import kotlin.reflect.KType
import kotlin.reflect.typeOf

@Serializable
enum class Role
{
    @SerialName("user")
    USER,

    @SerialName("system")
    SYSTEM,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}


@Serializable
data class TokenUsage(
    @SerialName("completion_tokens")
    val completionTokens: Long = 0,
    @SerialName("prompt_tokens")
    val promptTokens: Long = 0,
    @SerialName("total_tokens")
    val totalTokens: Long = 0,
)
{
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        completionTokens + other.completionTokens,
        promptTokens + other.promptTokens,
        totalTokens + other.totalTokens,
    )
}

@Serializable
sealed interface ContentNode
{
    val type: String

    companion object
    {
        @JvmStatic
        operator fun invoke(content: String): ContentNode = Text(content)
        @JvmStatic
        fun text(content: String): ContentNode = Text(content)
        @JvmStatic
        fun image(image: String): ContentNode = Image(Image.Image(image))
    }

    @Serializable
    data class Text(val text: String): ContentNode
    {
        override val type: String = "text"
    }

    @Serializable
    data class Image(@SerialName("image_url") val image: Image): ContentNode
    {
        override val type: String = "image_url"
        @Serializable data class Image(val url: String)
    }
}

@JvmInline
@Serializable
value class Content(@Serializable(ContentSerializer::class) val content: List<ContentNode>)
{
    constructor(vararg content: ContentNode): this(content.toList())
    constructor(content: String): this(listOf(ContentNode.Text(content)))

    operator fun plus(other: Content): Content = Content(content + other.content).optimize()

    operator fun plus(other: ContentNode): Content = Content(content + other).optimize()

    override fun toString(): String = content.joinToString(separator = "")

    fun optimize(): Content
    {
        val optimizedContent = mutableListOf<ContentNode>()
        for (node in content)
        {
            if (node is ContentNode.Text && optimizedContent.lastOrNull() is ContentNode.Text)
            {
                val lastTextNode = optimizedContent.last() as ContentNode.Text
                optimizedContent[optimizedContent.size - 1] = ContentNode.Text(lastTextNode.text + node.text)
            }
            else
            {
                optimizedContent.add(node)
            }
        }
        return Content(optimizedContent)
    }

    fun toText(): String
    {
        return content.joinToString(separator = "") { (it as? ContentNode.Text)?.text ?: "" }
    }

    fun isEmpty(): Boolean
    {
        return content.isEmpty() || content.all { it is ContentNode.Text && it.text.isBlank() }
    }

    private class ContentSerializer: KSerializer<List<ContentNode>>
    {
        private val serializer = ListSerializer(ContentNode.serializer())
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor = buildSerialDescriptor("AiRequest.Message.Content", PolymorphicKind.SEALED)
        {
            element("simple", String.serializer().descriptor)
            element("complex", serializer.descriptor)
        }

        override fun serialize(encoder: Encoder, value: List<ContentNode>)
        {
            if (value.all { it is ContentNode.Text })
                encoder.encodeString(value.joinToString(separator = "") { (it as ContentNode.Text).text })
            else
                serializer.serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): List<ContentNode>
        {
            val json = if (decoder is JsonDecoder) decoder.json else Json
            val ele = JsonElement.serializer().deserialize(decoder)
            return if (ele is JsonPrimitive) listOf(ContentNode.Text(ele.content))
            else json.decodeFromJsonElement(serializer, ele)
        }
    }
}

data class AiToolInfo<T: Any>(
    val name: String,
    val displayName: String,
    val description: String,
    val invoke: suspend (T) -> ToolResult,
    val parms: Parameters,
    val type: KType,
)
{
    @OptIn(ExperimentalSerializationApi::class)
    @SerialInfo
    @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
    annotation class Description(val value: String)


    @OptIn(ExperimentalSerializationApi::class)
    @SerialInfo
    @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
    annotation class EnumValues(val values: Array<String>)

    data class ToolResult(
        val content: Content,
        val showingContent: String? = null,
        val showingContentType: AiTools.ToolData.Type = AiTools.ToolData.Type.MARKDOWN,
    )

    companion object
    {
        inline operator fun <reified T: Any> invoke(
            name: String,
            displayName: String,
            description: String,
            noinline block: suspend (T) -> ToolResult
        ): AiToolInfo<T>
        {
            val type = typeOf<T>()
            val descriptor = serializer(type).descriptor
            val properties = mutableMapOf<String, Parameters.Property>()
            val required = mutableListOf<String>()
            for (i in 0 until descriptor.elementsCount)
            {
                val elementName = descriptor.getElementName(i)
                val elementDescription = descriptor.getElementAnnotations(i).filterIsInstance<Description>().firstOrNull()?.value
                val elementEnumValues = descriptor.getElementAnnotations(i).filterIsInstance<EnumValues>().firstOrNull()?.values
                val elementDescriptor = descriptor.getElementDescriptor(i)
                val property = when (elementDescriptor.kind)
                {
                    PrimitiveKind.STRING   -> Parameters.Property("string", elementDescription, elementEnumValues?.toList())

                    PrimitiveKind.LONG,
                    PrimitiveKind.INT,
                    PrimitiveKind.BYTE,
                    PrimitiveKind.SHORT    -> Parameters.Property("integer", elementDescription)

                    PrimitiveKind.DOUBLE,
                    PrimitiveKind.FLOAT    -> Parameters.Property("double", elementDescription)

                    PrimitiveKind.BOOLEAN  -> Parameters.Property("boolean", elementDescription)

                    else -> throw SerializationException("Unsupported type: $elementDescriptor")
                }
                properties[elementName] = property
                if (descriptor.isElementOptional(i).not())
                    required.add(elementName)
            }
            val parameters = Parameters(properties, required)
            return AiToolInfo(
                name = name,
                displayName = displayName,
                description = description,
                invoke = block,
                parms = parameters,
                type = type
            )
        }
    }

    @Serializable
    data class Parameters(
        @SerialName("properties") val properties: Map<String, Property>,
        @SerialName("required") val required: List<String> = emptyList(),
    )
    {
        val type = "object"
        @Serializable
        @OptIn(ExperimentalSerializationApi::class)
        data class Property(
            val type: String,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val description: String? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER) val enum: List<String>? = null,
        )
    }
}


typealias ChatMessages = List<ChatMessage>
fun ChatMessages(vararg messages: ChatMessage): ChatMessages = messages.toList()
fun ChatMessages(role: Role, content: String, reasoningContent: String = "", toolCallId: String = ""): ChatMessages =
    listOf(ChatMessage(role, content, reasoningContent, toolCallId))
fun ChatMessages(role: Role, content: Content, reasoningContent: String = "", toolCallId: String = ""): ChatMessages = listOf(ChatMessage(role, content, reasoningContent, toolCallId))
@Serializable
data class ChatMessage(
    val role: Role = Role.USER,
    val content: Content,
    @SerialName("reasoning_content")
    val reasoningContent: String = "",
    @SerialName("tool_call_id")
    val toolCallId: String = "",
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall> = emptyList(),
    val showingType: AiTools.ToolData.Type? = null
)
{
    constructor(role: Role, content: String, reasoningContent: String = "", toolCallId: String = ""):
            this(role, Content(content), reasoningContent, toolCallId, emptyList())
    init
    {
        if (toolCalls.isNotEmpty() && role != Role.ASSISTANT)
        {
            error("Only ASSISTANT role messages can have tool calls")
        }
        if (this.role != Role.TOOL && toolCallId.isNotEmpty())
        {
            error("Tool call ID should only be set for TOOL role messages")
        }
        else if (this.role == Role.TOOL && toolCallId.isEmpty())
        {
            error("Tool call ID must be set for TOOL role messages")
        }
    }

    operator fun plus(other: ChatMessage): ChatMessage
    {
        if (this.toolCallId.isNotEmpty() || other.toolCallId.isNotEmpty() || this.showingType != null || other.showingType != null)
        {
            error("Cannot combine messages with tool calls")
        }
        return ChatMessage(
            role = role,
            content = content + other.content,
            reasoningContent = reasoningContent + other.reasoningContent,
            toolCalls = toolCalls + other.toolCalls,
        )
    }

    @Serializable
    data class ToolCall(
        val id: String,
        val function: Function
    )
    {
        constructor(id: String, name: String, arguments: String):
                this(id, Function(name, arguments))
        val type = "function"
        val name: String
            get() = function.name
        val arguments: String
            get() = function.arguments
        @Serializable
        data class Function(
            val name: String,
            val arguments: String,
        )
    }
}

sealed interface StreamAiResponseSlice
{
    @Serializable
    data class Message(
        val content: String = "",
        @SerialName("reasoning_content")
        val reasoningContent: String = "",
    ): StreamAiResponseSlice

    data class ToolCall(
        val tool: AiToolInfo<*>,
    ): StreamAiResponseSlice

    @Serializable
    data class ShowingTool(
        val content: String,
        val showingType: AiTools.ToolData.Type,
    ): StreamAiResponseSlice
}