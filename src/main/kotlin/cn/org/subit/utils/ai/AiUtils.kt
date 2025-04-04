package cn.org.subit.utils.ai

import cn.org.subit.config.aiConfig
import cn.org.subit.database.Records
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.plugin.contentNegotiation.showJson
import cn.org.subit.utils.getKoin
import cn.org.subit.utils.httpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.koin.core.component.inject
import org.koin.mp.KoinPlatformTools
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val semaphore by lazy { Semaphore(aiConfig.maxConcurrency) }
private val logger = SubQuizLogger.getLogger()

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
data class AiResponse(
    @OptIn(ExperimentalUuidApi::class)
    val id: Uuid,
    val choices: List<Choice>,
    val created: Long,
    val model: String,
    val `object`: String,
    val usage: Usage,
)
{
    @Serializable
    data class Choice(
        @SerialName("finish_reason")
        val finishReason: FinishReason,
        val index: Int,
        val message: Message,
    )
    {
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

        @Serializable data class Message(
            val content: String,
            @SerialName("reasoning_content")
            val reasoningContent: String? = null,
        )
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = AiRequest.Serializer::class)
@KeepGeneratedSerializer
data class AiRequest(
    val model: String,
    val messages: List<Message>,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val responseFormat: ResponseFormat? = null,
    val stop: List<String>? = null,
)
{
    class Serializer: KSerializer<AiRequest>
    {
        private val _ser = generatedSerializer()
        override val descriptor: SerialDescriptor = _ser.descriptor
        override fun deserialize(decoder: Decoder): AiRequest = _ser.deserialize(decoder)
        override fun serialize(encoder: Encoder, value: AiRequest)
        {
            val c = encoder.beginStructure(descriptor)
            c.encodeStringElement(descriptor, 0, value.model)
            c.encodeSerializableElement(descriptor, 1, ListSerializer(Message.serializer()), value.messages)
            if (value.maxTokens != null) c.encodeIntElement(descriptor, 2, value.maxTokens)
            if (value.temperature != null) c.encodeDoubleElement(descriptor, 3, value.temperature)
            if (value.topP != null) c.encodeDoubleElement(descriptor, 4, value.topP)
            if (value.frequencyPenalty != null) c.encodeDoubleElement(descriptor, 5, value.frequencyPenalty)
            if (value.presencePenalty != null) c.encodeDoubleElement(descriptor, 6, value.presencePenalty)
            if (value.responseFormat != null) c.encodeSerializableElement(descriptor, 7, ResponseFormat.serializer(), value.responseFormat)
            if (value.stop != null) c.encodeSerializableElement(descriptor, 8, ListSerializer(String.serializer()), value.stop)
            c.endStructure(descriptor)
        }
    }

    @Serializable
    data class Message(
        val role: Role,
        val content: List<Content> = listOf(Content("")),
    )
    {

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
    url: String,
    key: String,
    model: String,
    messages: List<AiRequest.Message>,
    maxTokens: Int? = null,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    responseFormat: AiRequest.ResponseFormat? = null,
    stop: List<String>? = null,
): AiResponse = semaphore.withPermit<AiResponse>()
{
    val body = AiRequest(
        model = model,
        messages = messages,
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        responseFormat = responseFormat,
        stop = stop,
    )

    var res: AiResponse? = null
    try
    {
        res = withTimeout<AiResponse>(aiConfig.timeout)
        {
            logger.config("发送AI请求: $url")
            val res = httpClient.post(url)
            {
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body)
            }
            res.body<AiResponse>()
        }
        res!!
    }
    finally
    {
        records.addRecord(body, res)
    }
}