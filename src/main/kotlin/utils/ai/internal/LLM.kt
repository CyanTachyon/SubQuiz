@file:Suppress("unused", "PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.database.Records
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.tools.AiToolInfo
import moe.tachyon.quiz.utils.getKoin

@Serializable
private sealed interface AiResponse
{
    val id: String
    val `object`: String
    val model: String
    val usage: TokenUsage
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
            @SerialName("tool_calls")
            val toolCalls: List<ToolCall>?

            @Serializable
            data class ToolCall(
                val id: String? = null,
                val function: Function,
            )
            {
                @Suppress("RedundantNullableReturnType")
                val type: String? = "function"
                @Serializable
                data class Function(
                    val name: String? = null,
                    val arguments: String? = null,
                )
            }
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
}

@Serializable
private data class DefaultAiResponse(
    override val id: String,
    override val choices: List<Choice>,
    override val created: Long,
    override val model: String,
    override val `object`: String,
    override val usage: TokenUsage,
): AiResponse
{
    @Serializable
    data class Choice(
        @SerialName("finish_reason")
        override val finishReason: AiResponse.FinishReason? = null,
        override val index: Int,
        override val message: Message,
    ): AiResponse.Choice
    {
        @Serializable
        data class Message(
            override val content: String,
            @SerialName("reasoning_content")
            override val reasoningContent: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall>? = null,
        ): AiResponse.Choice.Message
    }
}

@Serializable
private data class StreamAiResponse(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    override val choices: List<Choice> = emptyList(),
    override val usage: TokenUsage = TokenUsage(),
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
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList(),
        ): AiResponse.Choice.Message
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class AiRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("max_tokens") val maxTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("temperature") val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("top_p") val topP: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("stop") val stop: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("tools") val tools: List<Tool> = emptyList(),
)
{
    @Serializable
    data class Message(
        val role: Role,
        val content: Content = Content(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_calls")
        val toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList(),
    )
    {
        operator fun plus(other: Message): Message
        {
            if (this.toolCallId != null || other.toolCallId != null)
            {
                error("Tool call ID should only be set for TOOL role messages")
            }
            return Message(
                role = role,
                content = content + other.content,
                toolCallId = null,
                toolCalls = toolCalls + other.toolCalls,
            )
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

    @Serializable
    data class Tool(val function: Function)
    {
        @Suppress("RedundantNullableReturnType")
        val type: String? = "function"

        @Serializable
        data class Function(
            val name: String,
            val description: String? = null,
            val parameters: JsonSchema? = null,
        )
    }
}

private fun ChatMessage.toRequestMessage(): AiRequest.Message?
{
    if (this.showingType != null) return null
    return AiRequest.Message(
        role = this.role,
        content = this.content,
        toolCallId = this.toolCallId.takeUnless { it.isEmpty() },
        toolCalls = this.toolCalls.map { AiResponse.Choice.Message.ToolCall(it.id, AiResponse.Choice.Message.ToolCall.Function(it.name, it.arguments)) },
    )
}

private val logger = SubQuizLogger.getLogger()

private val streamAiClient = HttpClient(CIO)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 0
    }
    install(SSE)
}

private val defaultAiClient = HttpClient(CIO)
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


private val records: Records by getKoin().inject()

sealed class StreamAiResult(val messages: ChatMessages, val usage: TokenUsage)
{
    class Success(messages: ChatMessages, usage: TokenUsage): StreamAiResult(messages, usage)
    class TooManyRequests(messages: ChatMessages, usage: TokenUsage) : StreamAiResult(messages, usage)
    class ServiceError(messages: ChatMessages, usage: TokenUsage) : StreamAiResult(messages, usage)
    class Cancelled(messages: ChatMessages, usage: TokenUsage) : StreamAiResult(messages, usage)
    class UnknownError(messages: ChatMessages, usage: TokenUsage, val error: Throwable) : StreamAiResult(messages, usage)
}

suspend fun sendAiRequest(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    record: Boolean = true,
) = model.semaphore.withPermit()
{
    val url = model.url

    val body = AiRequest(
        model = model.model,
        messages = messages.mapNotNull(ChatMessage::toRequestMessage),
        stream = false,
        maxTokens = model.maxTokens,
        thinkingBudget = model.thinkingBudget,
        temperature = temperature,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        stop = stop,
    )

    var res: JsonElement? = null
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
            val body = res.bodyAsText()
            logger.severe("AI请求返回不是合法JSON: $body")
            {
                contentNegotiationJson.parseToJsonElement(body)
            }.getOrThrow()
        }
        val r = runCatching()
        {
            contentNegotiationJson.decodeFromJsonElement<DefaultAiResponse>(res)
        }.onFailure()
        {
            logger.warning("AI请求返回异常: ${contentNegotiationJson.encodeToString(res)}")
        }.getOrThrow()

        ChatMessage(
            role = Role.ASSISTANT,
            content = r.choices.firstOrNull()?.message?.content ?: "",
            reasoningContent = r.choices.firstOrNull()?.message?.reasoningContent ?: "",
        ) to r.usage
    }
    finally
    {
        if (record) runCatching()
        {
            withContext(NonCancellable)
            {
                records.addRecord(url, body, res)
            }
        }
    }
}

suspend fun sendAiStreamRequest(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    record: Boolean = true,
    tools: List<AiToolInfo<*>> = emptyList(),
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): StreamAiResult = model.semaphore.withPermit()
{
    val url = model.url
    val body = AiRequest(
        model = model.model,
        messages = messages.mapNotNull(ChatMessage::toRequestMessage),
        stream = true,
        maxTokens = model.maxTokens,
        thinkingBudget = model.thinkingBudget,
        temperature = temperature,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        stop = stop,
        tools = tools.map()
        {
            AiRequest.Tool(
                function = AiRequest.Tool.Function(
                    name = it.name,
                    description = it.description,
                    parameters = it.dataSchema,
                )
            )
        },
    )

    val list = mutableListOf<StreamAiResponse>()
    val res = mutableListOf<ChatMessage>()
    var send = true
    var usage = TokenUsage()
    var curMsg = ChatMessage(Role.ASSISTANT, "")
    try
    {
        while (send)
        {
            val waitingTools = mutableMapOf<String, Pair<String, String>>()
            var lstToolId = ""
            streamAiClient.sse(url, {
                method = HttpMethod.Post
                bearerAuth(model.key.random())
                contentType(ContentType.Application.Json)
                accept(ContentType.Any)
                val rBody = contentNegotiationJson.encodeToString(body.copy(messages = body.messages + res.mapNotNull(ChatMessage::toRequestMessage)))
                logger.config("流式请求$url : $rBody")
                setBody(rBody)
            })
            {
                incoming
                    .mapNotNull { it.data }
                    .filterNot { it == "[DONE]" }
                    .mapNotNull {
                        logger.severe("AI流式请求返回异常: $it")
                        {
                            contentNegotiationJson.decodeFromString<StreamAiResponse>(it)
                        }.getOrNull()
                    }
                    .collect()
                    {
                        list.add(it)
                        usage += it.usage
                        it.choices.forEach()
                        { c ->
                            c.message
                                .toolCalls
                                .mapNotNull { tool -> tool.function.name }
                                .mapNotNull { func -> tools.firstOrNull { t -> t.name == func } }
                                .forEach { tool -> onReceive(StreamAiResponseSlice.ToolCall(tool)) }


                            c.message.toolCalls.forEach()
                            { tool ->
                                if (tool.id != null)
                                {
                                    waitingTools[tool.id] = "" to ""
                                    lstToolId = tool.id
                                }
                                if (!tool.function.name.isNullOrEmpty())
                                {
                                    val l = waitingTools[lstToolId]!!
                                    waitingTools[lstToolId] = l.first + tool.function.name to l.second
                                }
                                if (!tool.function.arguments.isNullOrEmpty())
                                {
                                    val l = waitingTools[lstToolId]!!
                                    waitingTools[lstToolId] = l.first to l.second + tool.function.arguments
                                }
                            }

                            if (!c.message.content.isNullOrEmpty() || !c.message.reasoningContent.isNullOrEmpty())
                            {
                                onReceive(
                                    StreamAiResponseSlice.Message(
                                        content = c.message.content ?: "",
                                        reasoningContent = c.message.reasoningContent ?: "",
                                    )
                                )
                            }
                            curMsg += ChatMessage(
                                role = Role.ASSISTANT,
                                content = c.message.content ?: "",
                                reasoningContent = c.message.reasoningContent ?: "",
                            )
                        }
                    }
            }
            waitingTools.forEach()
            {
                curMsg += ChatMessage(
                    role = Role.ASSISTANT,
                    content = Content(),
                    toolCalls = listOf(ChatMessage.ToolCall(it.key, it.value.first, it.value.second)),
                )
            }
            res += curMsg
            curMsg = ChatMessage(Role.ASSISTANT, "")
            if (waitingTools.isNotEmpty())
            {
                val parseToolCalls = parseToolCalls(waitingTools, tools)
                parseToolCalls.filter { it.showingType != null }.forEach()
                {
                    onReceive(StreamAiResponseSlice.ShowingTool(it.content.toText(), it.showingType!!))
                }
                res += parseToolCalls
            }
            else send = false
        }
    }
    catch (e: Throwable)
    {
        if (!curMsg.content.isEmpty() || !curMsg.reasoningContent.isEmpty()) res += curMsg
        return when (e)
        {
            is CancellationException -> StreamAiResult.Cancelled(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value == 429) -> StreamAiResult.TooManyRequests(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value in listOf(500, 502, 504)) -> StreamAiResult.ServiceError(res.toChatMessages(), usage)
            else -> StreamAiResult.UnknownError(res.toChatMessages(), usage, e)
        }
    }
    finally
    {
        if (record) runCatching()
        {
            withContext(NonCancellable)
            {
                records.addRecord(url, body, list)
            }
        }
    }
    StreamAiResult.Success(res.toChatMessages(), usage)
}

private suspend fun parseToolCalls(
    waitingTools: Map<String, Pair<String, String>>,
    tools: List<AiToolInfo<*>>
): ChatMessages
{
    return waitingTools.flatMap()
    { (id, t) ->
        val (toolName, parm) = t
        val tool = tools.firstOrNull { it.name == toolName } ?: return@flatMap run()
        {
            ChatMessages(
                role = Role.TOOL,
                content = "error: tool $toolName not found",
                toolCallId = id
            )
        }

        val data: Any
        try
        {
            data = contentNegotiationJson.decodeFromString(contentNegotiationJson.serializersModule.serializer(tool.type), parm)!!
        }
        catch (e: Throwable)
        {
            return@flatMap ChatMessages(
                Role.TOOL,
                "error: \n${e.stackTraceToString()}",
                toolCallId = id
            )
        }

        val content = try
        {
            logger.config("Calling tool $toolName with parameters $parm")
            @Suppress("UNCHECKED_CAST")
            (tool as AiToolInfo<Any>).invoke(data)
        }
        catch (e: Throwable)
        {
            logger.warning("Tool call failed for $toolName with parameters $parm", e)
            return@flatMap ChatMessages(
                role = Role.TOOL,
                content = "error: \n${e.stackTraceToString()}",
                toolCallId = id
            )
        }
        val toolCall = ChatMessage(
            role = Role.TOOL,
            content = content.content,
            toolCallId = id
        )
        val showingContents = content.showingContent.map()
        {
            ChatMessage(
                role = Role.ASSISTANT,
                content = Content(it.first),
                showingType = it.second,
            )
        }
        return@flatMap listOf(toolCall) + showingContents
    }.toChatMessages()
}