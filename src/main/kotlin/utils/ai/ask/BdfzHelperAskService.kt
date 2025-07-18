package moe.tachyon.quiz.utils.ai.ask

import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.ai.AiRequest
import moe.tachyon.quiz.utils.ai.StreamAiResponse
import moe.tachyon.quiz.utils.ai.streamAiClient
import org.koin.core.component.KoinComponent

/**
 * 北大附中问答助手问答服务
 */
object BdfzHelperAskService: AskService(), KoinComponent
{
    const val MODEL_NAME = "bdfz-helper"
    private val logger = SubQuizLogger.getLogger<BdfzHelperAskService>()

    @Serializable
    private data class RequestBody(
        val content: String,
        val history: List<AiRequest.Message>,
        val stream: Boolean = true,
    )

    override suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: List<AiRequest.Message>,
        content: String,
        onRecord: suspend (StreamAiResponse.Choice.Message)->Unit
    )
    {
        val prompt = makePrompt(section, false)
        val messages = listOf(prompt) + histories
        val body = RequestBody(content, messages)

        logger.config("发送问答助手请求: ${section?.id} - ${content.take(50)}")
        val serializedBody = contentNegotiationJson.encodeToString(body)
        try
        {
            streamAiClient.sse(aiConfig.bdfzHelper, {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                accept(ContentType.Any)
                setBody(serializedBody)
            })
            {
                incoming
                    .mapNotNull { it.data }
                    .filterNot { it == "[DONE]" }
                    .map { contentNegotiationJson.decodeFromString<StreamAiResponse>(it) }
                    .collect { it.choices.forEach { it1 -> onRecord(it1.message) } }
            }
        }
        catch (_: CancellationException)
        {
            // do nothing, cancellation is expected
        }
        catch (e: Throwable)
        {
            logger.warning("发送问答助手请求失败: $serializedBody", e)
        }
    }
}