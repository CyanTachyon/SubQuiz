@file:Suppress("unused")
@file:OptIn(ExperimentalUuidApi::class)

package moe.tachyon.quiz.utils.ai.internal.llm

import io.ktor.client.*
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
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.database.Records
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.chat.tools.AiToolInfo
import moe.tachyon.quiz.utils.getKoin
import moe.tachyon.quiz.utils.ktorClientEngineFactory
import moe.tachyon.quiz.utils.toJsonElement
import kotlin.collections.set
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
            val reasoningContent0: String? = null,
            @SerialName("reasoning")
            val reasoningContent1: String? = null,
            @SerialName("tool_calls")
            override val toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList(),
        ): AiResponse.Choice.Message
        {
            override val reasoningContent: String?
                get() = reasoningContent0 ?: reasoningContent1
        }
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
        @SerialName("reasoning_content")
        val reasoningContent0: String?,
        @SerialName("reasoning")
        val reasoningContent1: String?,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("tool_calls")
        val toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList(),
    )
    {
        constructor(
            role: Role,
            content: Content = Content(),
            reasoningContent: String?,
            toolCallId: String? = null,
            toolCalls: List<AiResponse.Choice.Message.ToolCall> = emptyList()
        ): this(role, content, reasoningContent, reasoningContent, toolCallId, toolCalls)
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

private fun ChatMessages.toRequestMessages(): List<AiRequest.Message>
{
    fun ChatMessage.toRequestMessage(): AiRequest.Message?
    {
        when (this.role)
        {
            Role.USER                               -> Unit
            Role.SYSTEM                             -> Unit
            Role.ASSISTANT if (showingType != null) -> return null
            Role.ASSISTANT                          -> Unit
            Role.TOOL                               -> Unit
            Role.CONTEXT_COMPRESSION                -> return null
        }

        return AiRequest.Message(
            role = this.role,
            content = this.content,
            reasoningContent = this.reasoningContent.takeUnless { it.isBlank() },
            toolCallId = this.toolCallId.takeUnless { it.isEmpty() },
            toolCalls = this.toolCalls.map { AiResponse.Choice.Message.ToolCall(it.id, AiResponse.Choice.Message.ToolCall.Function(it.name, it.arguments)) },
        )
    }

    return this.mapNotNull(ChatMessage::toRequestMessage)
}

private val logger = SubQuizLogger.getLogger()

private val streamAiClient = HttpClient(ktorClientEngineFactory)
{
    engine()
    {
        dispatcher = Dispatchers.IO
        requestTimeout = 0
    }
    install(SSE)
}

private val defaultAiClient = HttpClient(ktorClientEngineFactory)
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

sealed class AiResult(val messages: ChatMessages, val usage: TokenUsage)
{
    class Success(messages: ChatMessages, usage: TokenUsage): AiResult(messages, usage)
    class TooManyRequests(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class ServiceError(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class Cancelled(messages: ChatMessages, usage: TokenUsage) : AiResult(messages, usage)
    class UnknownError(messages: ChatMessages, usage: TokenUsage, val error: Throwable) : AiResult(messages, usage)
}

/**
 * 发送流式AI请求，通过`onReceive`回调接收流式响应。最终结果通过返回值返回。
 *
 * 该函数理应永远不会抛出任何错误，返回详见[AiResult]
 */
suspend fun sendAiRequest(
    model: AiConfig.LlmModel,
    messages: ChatMessages,
    maxTokens: Int? = null,
    temperature: Double? = null,
    topP: Double? = null,
    frequencyPenalty: Double? = null,
    presencePenalty: Double? = null,
    stop: List<String>? = null,
    record: Boolean = true,
    tools: List<AiToolInfo<*>> = emptyList(),
    plugins: List<LlmLoopPlugin> = emptyList(),
    stream: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit = {},
): AiResult = model.semaphore.withPermit()
{
    val res = mutableListOf<ChatMessage>()
    var send = true
    var usage = TokenUsage()
    try
    {
        val context = LlmLoopPlugin.Context(
            model,
            messages,
            maxTokens,
            temperature,
            topP,
            frequencyPenalty,
            presencePenalty,
            stop,
            tools,
            res,

            onReceive = onReceive,
            addTokenUsage = { usage += it },
        )

        plugins.filterIsInstance<BeforeLlmLoop>().forEach()
        {
            context(context)
            {
                it.beforeLoop()
            }
        }

        while (send)
        {
            val beforeLlmRequestContext = BeforeLlmRequest.BeforeRequestContext(context.allMessages)
            plugins.filterIsInstance<BeforeLlmRequest>().forEach()
            {
                context(context, beforeLlmRequestContext)
                {
                    it.beforeRequest()
                }
            }

            val url = context.model.url
            val key = context.model.key.random()
            val body = AiRequest(
                model = context.model.model,
                messages = beforeLlmRequestContext.requestMessage.toRequestMessages(),
                stream = stream,
                maxTokens = context.maxTokens,
                thinkingBudget = context.model.thinkingBudget,
                temperature = context.temperature,
                topP = context.topP,
                frequencyPenalty = context.frequencyPenalty,
                presencePenalty = context.presencePenalty,
                stop = context.stop,
                tools = context.tools.map()
                {
                    AiRequest.Tool(
                        function = AiRequest.Tool.Function(
                            name = it.name,
                            description = it.description,
                            parameters = it.dataSchema,
                        )
                    )
                },
            ).let(contentNegotiationJson::encodeToJsonElement)
                .jsonObject
                .plus(context.model.customRequestParms?.toJsonElement()?.jsonObject ?: emptyMap())
                .let(::JsonObject)

            val requestResult = sendRequest(url, key, body, stream, record, onReceive)

            plugins.filterIsInstance<AfterLlmResponse>().forEach()
            {
                context(context, requestResult)
                {
                    it.afterResponse()
                }
            }

            val (waitingTools, curMsg, usage0, e) = requestResult
            if (!curMsg.content.isEmpty() || !curMsg.reasoningContent.isEmpty() || !curMsg.toolCalls.isEmpty()) res += curMsg
            usage += usage0
            if (e != null) throw e
            if (waitingTools.isNotEmpty())
            {
                val parseToolCalls = parseToolCalls(waitingTools, tools, onReceive)
                res += parseToolCalls
                parseToolCalls.filter { it.showingType != null }.forEach()
                {
                    onReceive(StreamAiResponseSlice.ShowingTool(it.content.toText(), it.showingType!!))
                }
            }
            else send = false
        }
    }
    catch (e: Throwable)
    {
        return when (e)
        {
            is CancellationException -> AiResult.Cancelled(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value == 429) -> AiResult.TooManyRequests(res.toChatMessages(), usage)
            is SSEClientException if (e.response?.status?.value in listOf(500, 502, 504)) -> AiResult.ServiceError(res.toChatMessages(), usage)
            else -> AiResult.UnknownError(res.toChatMessages(), usage, e)
        }
    }
    AiResult.Success(res.toChatMessages(), usage)
}

data class RequestResult(
    var toolCalls : Map<String, Pair<String, String>>,
    var message: ChatMessage,
    var usage: TokenUsage,
    var error: Throwable?,
)

private suspend fun sendRequest(
    url: String,
    key: String,
    body: JsonElement,
    stream: Boolean,
    record: Boolean,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): RequestResult
{
    logger.config("AI请求$url : $body")

    if (!stream) runCatching()
    {
        val response = defaultAiClient.post(url)
        {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(body)
        }
        val responseBody = response.bodyAsText()
        val json = logger.severe("AI请求返回不是合法JSON: $responseBody")
        {
            contentNegotiationJson.parseToJsonElement(responseBody)
        }.getOrThrow()
        val res = runCatching()
        {
            contentNegotiationJson.decodeFromJsonElement<DefaultAiResponse>(json)
        }.onFailure()
        {
            logger.warning("AI请求返回异常: ${contentNegotiationJson.encodeToString(response)}")
        }.getOrThrow()
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            content = res.choices.joinToString { it.message.content }.let(::Content),
            reasoningContent = res.choices.joinToString { it.message.reasoningContent ?: "" }
        )
        val usage = res.usage
        if (record) runCatching()
        {
            withContext(NonCancellable)
            {
                records.addRecord(url, body, json)
            }
        }
        val calls = res.choices.mapNotNull { it.message.toolCalls }.flatten().mapNotNull()
        {
            it.id ?: return@mapNotNull null
            it.id to ((it.function.name ?: "") to (it.function.arguments ?: ""))
        }.toMap()
        return RequestResult(calls, msg, usage, null)
    }.onFailure()
    {
        runCatching()
        {
            withContext(NonCancellable)
            {
                records.addRecord(url, body, null)
            }
        }
        return RequestResult(emptyMap(), ChatMessage(Role.ASSISTANT, ""), TokenUsage(), it)
    }


    var usage0 = TokenUsage()
    val waitingTools = mutableMapOf<String, Pair<String, String>>()
    var lstToolId = ""
    var curMsg = ChatMessage(Role.ASSISTANT, "")
    val responses = mutableListOf<StreamAiResponse>()

    val r = runCatching()
    {
        streamAiClient.sse(url, {
            method = HttpMethod.Post
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            setBody(contentNegotiationJson.encodeToString(body))
        })
        {
            if (!call.response.status.isSuccess())
                throw SSEClientException(call.response, message = "AI请求返回异常，HTTP状态码 ${call.response.status.value}, 响应体: ${call.response.bodyAsText()}")
            incoming
                .mapNotNull { it.data }
                .filterNot { it == "[DONE]" }
                .mapNotNull {
                    contentNegotiationJson.decodeFromString<StreamAiResponse>(it)
                }
                .collect()
                {
                    responses += it
                    if (it.usage.totalTokens > usage0.totalTokens)
                        usage0 = it.usage
                    it.choices.forEach()
                    { c ->
                        c.message.toolCalls.forEach()
                        { tool ->
                            if (tool.id != null)
                            {
                                waitingTools[tool.id] = "" to ""
                                lstToolId = tool.id
                            }
                            if (!tool.function.name.isNullOrEmpty() || !tool.function.arguments.isNullOrEmpty())
                            {
                                val l = waitingTools[lstToolId]
                                if (l == null)
                                    logger.warning("出现错误: 工具调用ID丢失，无法将工具调用名称与参数对应。lstToolId=$lstToolId，waitingTools=$waitingTools, tool=$tool")
                                else
                                {
                                    val newName = l.first + (tool.function.name ?: "")
                                    val newArg = l.second + (tool.function.arguments ?: "")
                                    waitingTools[lstToolId] = newName to newArg
                                }
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
    }

    runCatching()
    {
        withContext(NonCancellable)
        {
            if (record) records.addRecord(url, body, responses)
        }
    }

    return RequestResult(waitingTools, curMsg, usage0, r.exceptionOrNull())
}

private suspend fun parseToolCalls(
    waitingTools: Map<String, Pair<String, String>>,
    tools: List<AiToolInfo<*>>,
    onReceive: suspend (StreamAiResponseSlice) -> Unit,
): ChatMessages
{
    return waitingTools.map()
    {
        val (id, t) = it
        if (id.isEmpty()) Uuid.random().toHexString() to t
        else id to t
    }.flatMap()
    { (id, t) ->
        val (toolName, parm) = t
        val tool = tools.firstOrNull { it.name == toolName } ?: return@flatMap run()
        {
            ChatMessages(
                role = Role.TOOL,
                content = "error: 工具$toolName 并不存在，请确认你调用的工具名称正确且存在",
                toolCallId = id
            )
        }

        logger.warning("fail to put toolcall message")
        {
            onReceive(StreamAiResponseSlice.ToolCall(tool, tool.display(parm)))
        }

        val data: Any
        try
        {
            data = tool.parse(parm)
        }
        catch (e: Throwable)
        {
            return@flatMap ChatMessages(
                Role.TOOL,
                "错误！你调用工具 $toolName 时传入的参数格式错误，请检查参数是否符合要求，并改正错误后重试。\n具体错误为：\n${e.message}",
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