@file:Suppress("unused")
package moe.tachyon.quiz.utils.ai

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import moe.tachyon.quiz.utils.ai.chat.tools.AiToolInfo
import moe.tachyon.quiz.utils.ai.chat.tools.AiTools
import java.util.function.IntFunction

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

    @Serializable
    CONTEXT_COMPRESSION
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
@OptIn(ExperimentalSerializationApi::class)
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
        @JvmStatic
        fun file(filename: String, url: String): ContentNode = File(File.File(filename, url))
    }

    @Serializable
    @SerialName("text")
    data class Text(val text: String): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "text"
    }

    @Serializable
    @SerialName("image_url")
    data class Image(@SerialName("image_url") val image: Image): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "image_url"
        @Serializable data class Image(val url: String)
    }

    @Serializable
    @SerialName("file")
    data class File(@SerialName("file") val file: File): ContentNode
    {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val type: String = "file"
        @Serializable data class File(@SerialName("filename") val filename: String, @SerialName("file_data") val url: String)
    }
}

@JvmInline
@Serializable
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
value class Content(@Serializable(ContentSerializer::class) val content: List<ContentNode>): List<ContentNode> by content
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

    override fun isEmpty(): Boolean
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

@Serializable
data class ChatMessage(
    val role: Role,
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

    infix fun canPlus(other: ChatMessage): Boolean =
        this.role == other.role &&
        this.showingType == null &&
        other.showingType == null &&
        this.role in listOf(Role.ASSISTANT)
    operator fun plus(other: ChatMessage): ChatMessage
    {
        if (!(this canPlus other)) error("Cannot combine messages with different roles, tool call IDs, or showing types")
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

@Serializable
@JvmInline
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
value class ChatMessages(private val messages: List<ChatMessage>): List<ChatMessage> by messages
{
    override fun subList(fromIndex: Int, toIndex: Int): ChatMessages =
        ChatMessages(messages.subList(fromIndex, toIndex))
    constructor(vararg messages: ChatMessage): this(messages.toList())
    constructor(role: Role, content: Content, reasoningContent: String = "", toolCallId: String = ""):
        this(ChatMessage(role, content, reasoningContent, toolCallId))
    constructor(role: Role, content: String, reasoningContent: String = "", toolCallId: String = ""):
            this(role, Content(content), reasoningContent, toolCallId)
    constructor(vararg messages: Pair<Role, Content>):
        this(messages.map { ChatMessage(it.first, it.second) })

    fun optimize(): ChatMessages
    {
        if (isEmpty()) return empty()
        val optimizedMessages = mutableListOf<ChatMessage>()
        var lastMessage: ChatMessage = first()
        for (message in drop(1))
        {
            if (lastMessage canPlus message)
                lastMessage += message
            else
            {
                optimizedMessages.add(lastMessage)
                lastMessage = message
            }
        }
        optimizedMessages.add(lastMessage)
        return optimizedMessages.toChatMessages()
    }

    operator fun plus(other: ChatMessage): ChatMessages =
        ChatMessages(this.messages + other).optimize()
    operator fun plus(other: Collection<ChatMessage>): ChatMessages =
        ChatMessages(this.messages + other).optimize()

    companion object
    {
        @JvmStatic
        fun empty(): ChatMessages = ChatMessages(emptyList())
        @JvmStatic
        fun of(vararg messages: ChatMessage): ChatMessages = ChatMessages(messages.toList())
        fun Iterable<ChatMessage>.toChatMessages(): ChatMessages = this as? ChatMessages ?: ChatMessages(this.toList())
        operator fun Iterable<ChatMessage>.plus(other: ChatMessages): ChatMessages =
            if (this is ChatMessages) this + other
            else this.toChatMessages() + other
    }
}

sealed interface StreamAiResponseSlice
{
    @Serializable
    data class Message(
        val content: String,
        @SerialName("reasoning_content")
        val reasoningContent: String = "",
    ): StreamAiResponseSlice

    data class ToolCall(
        val tool: AiToolInfo<*>,
        val display: AiToolInfo.DisplayToolInfo?,
    ): StreamAiResponseSlice

    @Serializable
    data class ShowingTool(
        val content: String,
        val showingType: AiTools.ToolData.Type,
    ): StreamAiResponseSlice

    /**
     * 发生了上下文压缩
     */
    data object ContextCompression: StreamAiResponseSlice
}