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
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.ai.AiRetryFailedException
import moe.tachyon.quiz.utils.ai.TokenUsage
import moe.tachyon.quiz.utils.ai.UnknownAiResponseException
import moe.tachyon.quiz.utils.ktorClientEngineFactory

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
    input: String,
): Pair<List<Double>, TokenUsage> = aiConfig.embedding.semaphore.withPermit()
{
    val request = AiRequest(
        model = aiConfig.embedding.model,
        input = listOf(input),
    )
    val errors = mutableListOf<Throwable>()
    repeat(aiConfig.retry)
    {
        runCatching()
        {
            val response = client.post(aiConfig.embedding.url)
            {
                bearerAuth(aiConfig.embedding.key.random())
                contentType(ContentType.Application.Json)
                setBody(request)
                accept(ContentType.Any)
            }.bodyAsText()
            val res = contentNegotiationJson.decodeFromString<AiResponse>(response)
            require(res.data.size == 1)
            {
                "The number of embeddings returned does not match the number of inputs."
            }
            return res.data.map(AiResponse.Data::embedding).first() to res.usage
        }.onFailure(errors::add)
    }
    throw errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
}