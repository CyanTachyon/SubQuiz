@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.embedding

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.ai.AiRetryFailedException
import moe.tachyon.quiz.utils.ai.TokenUsage
import moe.tachyon.quiz.utils.ai.UnknownAiResponseException
import moe.tachyon.quiz.utils.ktorClientEngineFactory

private val logger = SubQuizLogger.getLogger()
private val client = HttpClient(ktorClientEngineFactory)
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
private data class AiRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
private data class AiResponse(
    val data: List<Data>,
    val usage: TokenUsage,
)
{
    @Serializable
    data class Data(
        val embedding: List<Double>,
    )
}

suspend fun sendAiEmbeddingRequest(
    url: String,
    key: String,
    model: String,
    input: List<String>,
): Pair<List<List<Double>>, TokenUsage>
{
    val request = AiRequest(
        model = model,
        input = input,
    )
    val errors = mutableListOf<Throwable>()
    repeat(aiConfig.retry)
    {
        runCatching()
        {
            val response = client.post(url)
            {
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                setBody(request)
                accept(ContentType.Any)
            }.bodyAsText()
            val res = contentNegotiationJson.decodeFromString<AiResponse>(response)
            require(res.data.size == input.size)
            {
                "The number of embeddings returned does not match the number of inputs."
            }
            return res.data.map(AiResponse.Data::embedding) to res.usage
        }.onFailure(errors::add)
    }
    throw errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
}

suspend fun sendAiEmbeddingRequest(
    input: String,
): Pair<List<Double>, TokenUsage> = aiConfig.embedding.semaphore.withPermit()
{
    sendAiEmbeddingRequest(
        url = aiConfig.embedding.url,
        key = aiConfig.embedding.key.random(),
        model = aiConfig.embedding.model,
        input = listOf(input),
    ).let { (embeddings, usage) -> embeddings.first() to usage }
}