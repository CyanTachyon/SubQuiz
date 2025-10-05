@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.rerank

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.ai.AiRetryFailedException
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
private data class RerankRequest(
    val model: String,
    val query: String,
    val documents: List<String>,
)

@Serializable
private data class RerankResponse(
    val results: List<Result>,
)
{
    @Serializable
    data class Result(
        val index: Int,
        @SerialName("relevance_score")
        val relevanceScore: Double,
    )
}

suspend fun sendRerankRequest(
    query: String,
    documents: List<String>,
): List<String> = aiConfig.reranker.semaphore.withPermit()
{
    val request = RerankRequest(
        model = aiConfig.reranker.model,
        query = query,
        documents = documents,
    )

    logger.config("sending rerank request to ${aiConfig.reranker.url} with: ${showJson.encodeToString(request)}")

    val errors = mutableListOf<Throwable>()
    repeat(aiConfig.retry)
    {
        runCatching()
        {
            val response = client.post(aiConfig.reranker.url)
            {
                contentType(ContentType.Application.Json)
                bearerAuth(aiConfig.reranker.key.random())
                setBody(request)
            }.bodyAsText()
            val res = contentNegotiationJson.decodeFromString<RerankResponse>(response)
            require(res.results.all { it.index < documents.size })
            {
                "Some results have an index that is out of bounds for the documents list."
            }
            return res.results.map { documents[it.index] }
        }.onFailure(errors::add)
    }
    throw errors.map(::UnknownAiResponseException).let(::AiRetryFailedException)
}