package moe.tachyon.quiz.utils.ai.tools

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.ai.AiToolInfo
import moe.tachyon.quiz.utils.ai.Content
import kotlin.collections.plusAssign

object WebSearch
{
    private const val SEARCH_URL = "https://api.tavily.com/search"

    private val client = HttpClient(CIO)
    {
        engine()
        {
            dispatcher = Dispatchers.IO
            requestTimeout = 15_000
        }
        install(ContentNegotiation)
        {
            json(contentNegotiationJson)
        }
    }

    @Serializable
    data class Results<T>(val results: List<T>)

    @Serializable
    data class SearchResult(
        val url: String,
        val title: String,
        val content: String,
    )

    @Serializable
    private data class SearchRequest(
        val query: String,
        val count: Int,
    )

    suspend fun search(key: String, count: Int = 5): List<SearchResult> = withContext(Dispatchers.IO)
    {
        val res = client.post(SEARCH_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(aiConfig.webSearchKey)
            setBody(SearchRequest(query = key, count = count))
        }
        res.body<Results<SearchResult>>().results
    }

    private const val EXTRACT_URL = "https://api.tavily.com/extract"

    @Serializable
    data class ExtractResult(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
    )

    @Serializable
    private data class ExtractRequest(
        val urls: String,
    )

    suspend fun extract(url: String): ExtractResult = withContext(Dispatchers.IO)
    {
        val res = client.post(EXTRACT_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(aiConfig.webSearchKey)
            setBody(ExtractRequest(urls = url))
        }
        val list = res.body<Results<ExtractResult>>().results
        assert(list.size == 1) { "Expected exactly one result, got ${list.size}" }
        list.first()
    }

    @Serializable
    data class AiSearchToolData(@AiToolInfo.Description("搜索关键字") val key: String)
    @Serializable
    data class AiExtractToolData(@AiToolInfo.Description("要请求的url") val url: String)

    val tools: List<AiToolInfo<*>>

    init
    {
        val tools = mutableListOf<AiToolInfo<*>>()
        tools += AiToolInfo(
            name = "web_search",
            displayName = "联网搜索",
            description = "进行网络搜索，将返回若干相关的搜索结果及来源url等",
        )
        { it: AiSearchToolData ->
            val data = it.key
            if (data.isBlank()) Content("error: key must not be empty")
            else Content(showJson.encodeToString(search(data)))
        }

        tools += AiToolInfo(
            name = "web_extract",
            displayName = "获取网页内容",
            description = "提取网页内容，将读取指定url的内容并返回",
        )
        { it: AiExtractToolData ->
            val data = it.url
            if (data.isBlank()) Content("error: url must not be empty")
            else Content(showJson.encodeToString(extract(data)))
        }

        this.tools = tools
    }
}