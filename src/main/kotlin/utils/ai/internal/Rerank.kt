@file:Suppress("PackageDirectoryMismatch")

package moe.tachyon.quiz.utils.ai.internal.rerank

import io.ktor.client.*
import io.ktor.client.engine.cio.*
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

private val logger = SubQuizLogger.getLogger()
private val client = HttpClient(CIO)
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
    url: String,
    key: String,
    model: String,
    query: String,
    documents: List<String>,
): List<String>
{
    val request = RerankRequest(
        model = model,
        query = query,
        documents = documents,
    )

    logger.config("sending rerank request to $url with: ${showJson.encodeToString(request)}")

    val response = client.post(url)
    {
        contentType(ContentType.Application.Json)
        bearerAuth(key)
        setBody(request)
    }.bodyAsText()

    return logger.warning("failed to get rerank response: $response")
    {
        val res = contentNegotiationJson.decodeFromString<RerankResponse>(response)
        require(res.results.all { it.index < documents.size })
        {
            "Some results have an index that is out of bounds for the documents list."
        }
        res
    }.getOrThrow().let { res -> res.results.map { documents[it.index] } }
}

suspend fun sendRerankRequest(
    query: String,
    documents: List<String>,
): List<String> = aiConfig.reranker.semaphore.withPermit()
{
    sendRerankRequest(
        url = aiConfig.reranker.url,
        key = aiConfig.reranker.key.random(),
        model = aiConfig.reranker.model,
        query = query,
        documents = documents,
    )
}