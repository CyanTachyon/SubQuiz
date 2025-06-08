package cn.org.subit.utils.ai.ask

import cn.org.subit.config.aiConfig
import cn.org.subit.dataClass.Section
import cn.org.subit.logger.SubQuizLogger
import cn.org.subit.plugin.contentNegotiation.contentNegotiationJson
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.StreamAiResponse
import cn.org.subit.utils.sseClient
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable

/**
 * 北大附中问答助手问答服务
 */
object BdfzHelperAskService: AskService()
{
    private val logger = SubQuizLogger.getLogger<BdfzHelperAskService>()

    @Serializable
    private data class RequestBody(
        val content: String,
        val history: List<AiRequest.Message>,
        val stream: Boolean = true,
    )

    override suspend fun ask(
        section: Section<Any, Any, String>,
        histories: List<AiRequest.Message>,
        content: String,
        onRecord: suspend (StreamAiResponse.Choice.Message)->Unit
    )
    {
        val prompt = makePrompt(section, false)
        val messages = listOf(prompt) + histories
        val body = RequestBody(content, messages)

        logger.config("发送问答助手请求: ${section.id} - ${content.take(50)}")
        val serializedBody = contentNegotiationJson.encodeToString(body)
        runCatching()
        {
            sseClient.sse(aiConfig.bdfzHelper, {
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
        }.onFailure()
        {
            logger.warning("发送问答助手请求失败: $serializedBody", it)
        }
    }
}