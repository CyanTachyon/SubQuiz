package moe.tachyon.quiz.utils.ai.chat.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ktorClientEngineFactory

object WebSearch
{
    private val logger = SubQuizLogger.getLogger<WebSearch>()
    private const val SEARCH_URL = "https://api.tavily.com/search"

    private val client = HttpClient(ktorClientEngineFactory)
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
    private data class Results<T>(val results: List<T>)

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
        val body = res.bodyAsText()
        logger.warning("failed to parse search response: $body")
        {
            contentNegotiationJson.decodeFromString<Results<SearchResult>>(body).results
        }.getOrThrow()
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

    suspend fun extract(url: String): List<ExtractResult> = withContext(Dispatchers.IO)
    {
        val res = client.post(EXTRACT_URL)
        {
            contentType(ContentType.Application.Json)
            accept(ContentType.Any)
            bearerAuth(aiConfig.webSearchKey)
            setBody(ExtractRequest(urls = url))
        }
        res.body<Results<ExtractResult>>().results
    }

    @Serializable
    private data class AiSearchToolData(
        @JsonSchema.Description("搜索关键字")
        val key: String,
        @JsonSchema.Description("返回结果数量，不得超过20, 不填默认为10")
        val count: Int = 10,
    )
    @Serializable
    private data class AiExtractToolData(@JsonSchema.Description("要请求的url") val url: String)

    init
    {
        AiTools.registerTool<AiSearchToolData>(
            name = "web_search",
            displayName = "联网搜索",
            description = """
                进行网络搜索，将返回若干相关的搜索结果及来源url等，如有需要可以再使用`web_extract`工具提取网页内容
            """.trimIndent(),
            display = {
                if (it.parm != null)
                    Content("查找网页: ${it.parm.key.split(" ").joinToString(" ") { s -> "`$s`" }}")
                else Content()
            }
        )
        { (chat, model, parm) ->
            val data = parm.key
            if (data.isBlank()) AiToolInfo.ToolResult(Content("error: key must not be empty"))
            else AiToolInfo.ToolResult(Content(showJson.encodeToString(search(data, parm.count.coerceIn(1, 20))) +
                    "请在你后面的回答中添加信息来源标记，type为 `web`，path为网页url，例如:\n<data type=\"web\" path=\"https://example.com\">"))
        }

        AiTools.registerTool<AiExtractToolData>(
            name = "web_extract",
            displayName = "获取网页内容",
            description = """
                提取网页内容，将读取指定url的内容并返回
            """.trimIndent(),
            display = {
                if (it.parm != null)
                    Content("读取网页: [${it.parm.url}](${it.parm.url})")
                else Content()
            }
        )
        { (chat, model, parm) ->
            val data = parm.url
            if (data.isBlank()) AiToolInfo.ToolResult(Content("error: url must not be empty"))
            else AiToolInfo.ToolResult(Content(showJson.encodeToString(extract(data)) +
                    "请在你后面的回答中添加信息来源标记，type为 `web`，path为网页url，例如:\n<data type=\"web\" path=\"https://example.com\">"))
        }

        AiTools.registerToolDataGetter("web")
        { _, path ->
            AiTools.ToolData(
                type = AiTools.ToolData.Type.URL,
                value = path,
            )
        }
    }
}