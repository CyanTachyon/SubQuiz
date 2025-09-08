@file:Suppress("unused")

package moe.tachyon.quiz.utils.ai.internal.llm

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.utils.ai.ChatMessage
import moe.tachyon.quiz.utils.ai.ChatMessages
import moe.tachyon.quiz.utils.ai.StreamAiResponseSlice
import moe.tachyon.quiz.utils.ai.TokenUsage
import moe.tachyon.quiz.utils.ai.chat.tools.AiToolInfo
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin.Context

sealed interface LlmLoopPlugin
{
    data class Context(
        var model: AiConfig.LlmModel,
        var messages: ChatMessages,
        var maxTokens: Int?,
        var temperature: Double?,
        var topP: Double?,
        var frequencyPenalty: Double?,
        var presencePenalty: Double?,
        var stop: List<String>?,
        var tools: List<AiToolInfo<*>>,
        val responseMessage: MutableList<ChatMessage>,

        val addTokenUsage: (TokenUsage) -> Unit,
        val onReceive: suspend (StreamAiResponseSlice) -> Unit,
    )
    {
        val allMessages: ChatMessages get() = messages + responseMessage
    }
}

context(c: Context) var model get() = c.model; set(value) { c.model = value }
context(c: Context) var messages get() = c.messages; set(value) { c.messages = value }
context(c: Context) var maxTokens get() = c.maxTokens; set(value) { c.maxTokens = value }
context(c: Context) var temperature get() = c.temperature; set(value) { c.temperature = value }
context(c: Context) var topP get() = c.topP; set(value) { c.topP = value }
context(c: Context) var frequencyPenalty get() = c.frequencyPenalty; set(value) { c.frequencyPenalty = value }
context(c: Context) var presencePenalty get() = c.presencePenalty; set(value) { c.presencePenalty = value }
context(c: Context) var stop get() = c.stop; set(value) { c.stop = value }
context(c: Context) var tools get() = c.tools; set(value) { c.tools = value }
context(c: Context) val responseMessage get() = c.responseMessage
context(c: Context) val allMessages get() = c.allMessages
context(c: Context) fun addTokenUsage(u: TokenUsage) = c.addTokenUsage(u)
context(c: Context) suspend fun onReceive(slice: StreamAiResponseSlice) = c.onReceive(slice)


interface BeforeLlmRequest: LlmLoopPlugin
{
    data class BeforeRequestContext(
        var requestMessage: ChatMessages
    )

    context(_: Context, _: BeforeRequestContext)
    suspend fun beforeRequest()

    class Default(private val block: suspend context(Context, BeforeRequestContext) () -> Unit): BeforeLlmRequest
    {
        context(_: Context, _: BeforeRequestContext)
        override suspend fun beforeRequest() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context, BeforeRequestContext) () -> Unit) = Default(block)
    }
}

context(c: BeforeLlmRequest.BeforeRequestContext) var requestMessage get() = c.requestMessage; set(value) { c.requestMessage = value }

interface BeforeLlmLoop: LlmLoopPlugin
{
    context(_: Context)
    suspend fun beforeLoop()

    class Default(private val block: suspend context(Context) () -> Unit): BeforeLlmLoop
    {
        context(_: Context)
        override suspend fun beforeLoop() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context) () -> Unit) = Default(block)
    }
}

interface AfterLlmResponse: LlmLoopPlugin
{
    context(_: Context, _: RequestResult)
    suspend fun afterResponse()

    class Default(private val block: suspend context(Context, RequestResult) () -> Unit): AfterLlmResponse
    {
        context(_: Context, _: RequestResult)
        override suspend fun afterResponse() = block()
    }

    companion object
    {
        operator fun invoke(block: suspend context(Context, RequestResult) () -> Unit) = Default(block)
    }
}

context(c: RequestResult) var toolCalls : Map<String, Pair<String, String>> get() = c.toolCalls; set(value) { c.toolCalls = value }
context(c: RequestResult) var message: ChatMessage get() = c.message; set(value) { c.message = value }
context(c: RequestResult) var usage: TokenUsage get() = c.usage; set(value) { c.usage = value }
context(c: RequestResult) var error: Throwable? get() = c.error; set(value) { c.error = value }