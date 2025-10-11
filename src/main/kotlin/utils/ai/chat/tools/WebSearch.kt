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

object WebSearch: AiToolSet.ToolProvider
{
    override val name: String get() = "网络搜索"
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
            bearerAuth(aiConfig.webSearchKey.random())
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
            bearerAuth(aiConfig.webSearchKey.random())
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

    override suspend fun AiToolSet.registerTools()
    {
        registerTool<AiSearchToolData>(
            name = "web_search",
            displayName = "联网搜索",
            description = """
                进行网络搜索，将返回若干相关的搜索结果及来源url等，如有需要可以再使用`web_extract`工具提取网页内容
            """.trimIndent(),
        )
        {
            sendMessage("查找网页: ${parm.key.split(" ").joinToString(" ") { s -> "`$s`" }}")
            val data = parm.key
            if (data.isBlank()) AiToolInfo.ToolResult(Content("error: key must not be empty"))
            else AiToolInfo.ToolResult(Content(showJson.encodeToString(search(data, parm.count.coerceIn(1, 20))) +
                    "请在你后面的回答中添加信息来源标记，type为 `web`，path为网页url，例如:\n<data type=\"web\" path=\"https://example.com\" />"))
        }

        registerTool<AiExtractToolData>(
            name = "web_extract",
            displayName = "获取网页内容",
            description = """
                提取网页内容，将读取指定url的内容并返回
            """.trimIndent(),
        )
        {
            sendMessage("读取网页: [${parm.url}](${parm.url})")
            val data = parm.url
            if (data.isBlank()) AiToolInfo.ToolResult(Content("error: url must not be empty"))
            else AiToolInfo.ToolResult(Content(showJson.encodeToString(extract(data)) +
                    "请在你后面的回答中添加信息来源标记，type为 `web`，path为网页url，例如:\n<data type=\"web\" path=\"https://example.com\" />"))
        }

        registerToolDataGetter("web")
        { _, path ->
            AiToolSet.ToolData(
                type = AiToolSet.ToolData.Type.URL,
                value = path,
            )
        }
    }
}