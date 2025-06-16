@file:Suppress("unused")

package cn.org.subit.utils.ai

import cn.org.subit.config.AiConfig
import cn.org.subit.config.aiConfig
import cn.org.subit.database.Records
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.utils.getKoin
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
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

private val logger = SubQuizLogger.getLogger()

val streamAiClient = HttpClient(CIO)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 0
    }
    install(SSE)
}

val defaultAiClient = HttpClient(CIO)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 0
    }
    install(ContentNegotiation)
    {
        json(contentNegotiationJson)
    }
}

@Serializable
enum class Role
{
    @SerialName("user")
    USER,

    @SerialName("system")
    SYSTEM,

    @SerialName("assistant")
    ASSISTANT,
}

@Serializable
sealed interface AiResponse
{
    val id: String
    val `object`: String
    val model: String
    val usage: Usage
    val created: Long
    val choices: List<Choice>

    @Serializable
    sealed interface Choice
    {
        val finishReason: FinishReason?
        val index: Int
        val message: Message

        @Serializable
        sealed interface Message
        {
            val content: String?
            @SerialName("reasoning_content")
            val reasoningContent: String?
        }
    }

    @Serializable
    enum class FinishReason
    {
        @SerialName("stop")
        STOP,

        @SerialName("length")
        LENGTH,

        @SerialName("content_filter")
        CONTENT_FILTER,

        @SerialName("tool_calls")
        TOOL_CALLS,

        @SerialName("insufficient_system_resource")
        INSUFFICIENT_SYSTEM_RESOURCE,
    }

    @Serializable
    data class Usage(
        @SerialName("completion_tokens")
        val completionTokens: Long = 0,
        @SerialName("prompt_tokens")
        val promptTokens: Long = 0,
        @SerialName("total_tokens")
        val totalTokens: Long = 0,
    )
    {
        operator fun plus(other: Usage): Usage = Usage(
            completionTokens + other.completionTokens,
            promptTokens + other.promptTokens,
            totalTokens + other.totalTokens,
        )
    }
}

@Serializable
data class DefaultAiResponse(
    override val id: String,
    override val choices: List<Choice>,
    override val created: Long,
    override val model: String,
    override val `object`: String,
    override val usage: AiResponse.Usage,
): AiResponse
{
    @Serializable
    data class Choice(
        @SerialName("finish_reason")
        override val finishReason: AiResponse.FinishReason,
        override val index: Int,
        override val message: Message,
    ): AiResponse.Choice
    {
        @Serializable
        data class Message(
            override val content: String,
            @SerialName("reasoning_content")
            override val reasoningContent: String?,
        ): AiResponse.Choice.Message
    }
}

@Serializable
data class StreamAiResponse(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    override val choices: List<Choice>,
    override val usage: AiResponse.Usage = AiResponse.Usage(),
): AiResponse
{
    @Serializable
    data class Choice(
        override val index: Int,
        @SerialName("finish_reason")
        override val finishReason: AiResponse.FinishReason? = null,
        @SerialName("delta")
        override val message: Message,
    ): AiResponse.Choice
    {
        @Serializable
        data class Message(
            override val content: String? = null,
            @SerialName("reasoning_content")
            override val reasoningContent: String? = null,
        ): AiResponse.Choice.Message
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AiRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("max_tokens") val maxTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("temperature") val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("top_p") val topP: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stop") val stop: List<String>? = null,
)
{
    @Serializable
    data class Message(
        val role: Role,
        @Serializable(ContentSerializer::class)
        val content: List<Content> = listOf(Content("")),
    )
    {
        constructor(role: Role, content: String): this(role, listOf(Content(content)))

        @Serializable
        sealed interface Content
        {
            val type: String
            companion object
            {
                @JvmStatic operator fun invoke(content: String): Content = TextContent(content)
                @JvmStatic fun text(content: String): Content = TextContent(content)
                @JvmStatic fun image(image: String): Content = ImageContent(ImageContent.Image(image))
            }
        }

        @Serializable
        data class TextContent(val text: String): Content
        {
            override val type: String = "text"
        }

        @Serializable
        data class ImageContent(@SerialName("image_url") val image: Image): Content
        {
            override val type: String = "image_url"
            @Serializable data class Image(val url: String)
        }

        private class ContentSerializer: KSerializer<List<Content>>
        {
            private val serializer = ListSerializer(Content.serializer())
            @OptIn(InternalSerializationApi::class)
            override val descriptor: SerialDescriptor = buildSerialDescriptor("AiRequest.Message.Content", PolymorphicKind.SEALED)
            {
                element("simple", String.serializer().descriptor)
                element("complex", serializer.descriptor)
            }

            override fun serialize(encoder: Encoder, value: List<Content>)
            {
                if (value.all { it is TextContent })
                    encoder.encodeString(value.joinToString(separator = "") { (it as TextContent).text })
                else
                    serializer.serialize(encoder, value)
            }

            override fun deserialize(decoder: Decoder): List<Content>
            {
                val json = if (decoder is JsonDecoder) decoder.json else Json
                val ele = JsonElement.serializer().deserialize(decoder)
                if (ele is JsonPrimitive) return listOf(TextContent(ele.content))
                else
                {
                    return json.decodeFromJsonElement(serializer, ele)
                }
            }
        }
    }

    @Serializable
    data class ResponseFormat(
        val type: Type,
    )
    {
        @Serializable
        enum class Type
        {
            @SerialName("json_object")
            JSON,

            @SerialName("text")
            TEXT,
        }
    }
}

private val records: Records by getKoin().inject()

suspend fun sendAiRequest(
    model: AiConfig.Model,
    messages: List<AiRequest.Message>,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    responseFormat: AiRequest.ResponseFormat? = null,
    stop: List<String>? = null,
    record: Boolean = true,
) = model.semaphore.withPermit()
{
    val url = model.url

    val body = AiRequest(
        model = model.model,
        messages = messages,
        stream = false,
        maxTokens = model.maxTokens,
        temperature = temperature,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        responseFormat = responseFormat,
        stop = stop,
    )

    var res: DefaultAiResponse? = null
    try
    {
        res = withTimeout(aiConfig.timeout)
        {
            logger.config("发送AI请求: $url")
            val res = defaultAiClient.post(url)
            {
                bearerAuth(model.key.random())
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body)
            }
            res.body<DefaultAiResponse>()
        }
        res
    }
    finally
    {
        if (record) records.addRecord(url, body, res)
    }
}

suspend fun sendAiStreamRequest(
    model: AiConfig.Model,
    messages: List<AiRequest.Message>,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    responseFormat: AiRequest.ResponseFormat? = null,
    stop: List<String>? = null,
    record: Boolean = true,
    onReceive: suspend (StreamAiResponse) -> Unit,
) = model.semaphore.withPermit()
{
    val url = model.url
    val body = AiRequest(
        model = model.model,
        messages = messages,
        stream = true,
        maxTokens = model.maxTokens,
        temperature = temperature,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        responseFormat = responseFormat,
        stop = stop,
    )

    logger.config("发送AI流式请求: $url")
    val serializedBody = contentNegotiationJson.encodeToString(body)
    val list = mutableListOf<StreamAiResponse>()
    runCatching()
    {
        streamAiClient.sse(url,{
            method = HttpMethod.Post
            bearerAuth(model.key.random())
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            setBody(serializedBody)
        })
        {
            incoming
                .mapNotNull { it.data }
                .filterNot { it == "[DONE]" }
                .map { contentNegotiationJson.decodeFromString<StreamAiResponse>(it) }
                .collect()
                {
                    list.add(it)
                    onReceive(it)
                }
        }
    }.onFailure { logger.warning("发送AI流式请求请求失败: $serializedBody", it) }
    if (record) records.addRecord(url, body, list)
}