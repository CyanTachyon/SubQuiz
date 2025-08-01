package moe.tachyon.quiz.utils.ai.tools

import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.rag.Rag
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.FileUtils
import moe.tachyon.quiz.utils.ai.AiToolInfo
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.embedding.sendAiEmbeddingRequest
import moe.tachyon.quiz.utils.ai.internal.rerank.sendRerankRequest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max

object AiLibrary: KoinComponent
{
    private val logger = SubQuizLogger.getLogger<AiLibrary>()
    private val rag: Rag by inject()

    suspend fun updateLibrary()
    {
        val indexed = rag.getAllFiles()
        val files = FileUtils.getAiLibraryFiles(true, setOf("md"))
        files.forEach()
        { file ->
            updateLibrary(file, "", indexed)
        }
    }

    private suspend fun updateLibrary(file: FileUtils.FileInfo, prefix: String, exclude: Set<String>)
    {
        if (file is FileUtils.FileInfo.Directory) file.files.forEach()
        {
            updateLibrary(it, "$prefix/${file.name}", exclude)
        }
        else if (file is FileUtils.FileInfo.NormalFile)
        {
            val filePath = "$prefix/${file.name}"
            if (filePath in exclude) return
            logger.info("Inserting file: $filePath")
            logger.warning("file $filePath insert failed")
            {
                rag.insert(filePath, sendAiEmbeddingRequest(file.content).first)
            }
        }
    }

    suspend fun clear() = rag.removeAll()

    suspend fun remove(filePath: String) = rag.remove(filePath)

    suspend fun getAllFiles() = rag.getAllFiles()

    fun getFileText(filePath: String) = FileUtils.getAiLibraryFileText(filePath)
    fun getFileBytes(filePath: String) = FileUtils.getAiLibraryFileBytes(filePath)

    suspend fun search(prefix: String, query: String, count: Int): List<Pair<String, Double>>
    {
        val vector = sendAiEmbeddingRequest(query)
        val results = rag.query(prefix, vector.first, count)
        return results
    }

    suspend fun searchInOrder(prefix: String, query: String, count: Int): List<String>
    {
        val searchCount =
            if (count <= 2) 5
            else if (count <= 5) count * 3
            else if (count <= 10) max(count * 2, 15)
            else max(count + count / 2, 20)

        val results = search(prefix, query, searchCount).map()
        {
            FileUtils.getAiLibraryFileText(it.first) ?: "${it.first} (文件不存在)"
        }
        if (results.isEmpty()) return listOf("未找到相关文件")
        if (results.size <= count) return results
        return sendRerankRequest(query, results).subList(0, count)
    }

    @Serializable
    data class ToolParam(
        @AiToolInfo.Description("搜索内容")
        val query: String,
        @AiToolInfo.Description("返回结果数量, 不填默认为3")
        val count: Int = 3,
    )

    init
    {
        ///// bdfz data /////

        AiTools.registerTool<ToolParam>(
            "bdfz_data_search",
            "搜索北大附中资料",
            """
            在北大附中资料库中搜索相关内容, 将返回相关文档
            搜索的数量由 count 参数决定, 不宜过多，否则获得的文档可能过多，建议2到5条较为合适
            
            example:
            - {"query": "北大附中有哪些书院"}
            - {"query": "北大附中 数学课", "count": 5}
            
            **注意**: 使用该工具获得的信息后，你需要添加信息来源标记，type为 `lib`，path为文档路径，例如:
            <tool_data type="lib" path="/path/to/document.md">
        """.trimIndent(),
        )
        {
            val res = searchInOrder("/bdfz/", it.query, it.count)
            AiToolInfo.ToolResult(Content(showJson.encodeToString(res)))
        }

        AiTools.registerToolDataGetter("lib")
        { _, path ->
            val file = getFileText(path) ?: return@registerToolDataGetter null
            AiTools.ToolData(
                type = if (path.endsWith(".md")) AiTools.ToolData.Type.MARKDOWN else AiTools.ToolData.Type.TEXT,
                value = file,
            )
        }

        ///// books /////

        AiTools.registerTool<ToolParam>(
            "books_search",
            "搜索教科书",
            """
            在教科书资料库中搜索相关内容, 将返回相关文档.
            搜索的数量由 count 参数决定, 不宜过多，否则获得的文档可能过多，建议2到5条较为合适
            为保证你的回答的准确性，建议你在需要时调用该工具进行搜索，以确认课本上的表述等以保证回答的准确性
            example:
            - {"query": "加速度定义"}
            
            **注意**: 使用该工具获得的信息后，你需要添加信息来源标记，type为 `book`，path为文档路径，例如:
            <tool_data type="book" path="/path/to/book.md">
        """.trimIndent(),
        )
        {
            val res = searchInOrder("/books/", it.query, it.count)
            AiToolInfo.ToolResult(Content(showJson.encodeToString(res)))
        }

        AiTools.registerToolDataGetter("book")
        { _, path ->
            val file = getFileBytes(path.removeSuffix(".md") + ".png") ?: return@registerToolDataGetter null
            val image = ImageIO.read(file.inputStream())
            val image1 = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image1.createGraphics()
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image1.width, image1.height)
            graphics.drawImage(image, 0, 0, null)
            graphics.dispose()
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image1, "png", outputStream)
            outputStream.flush()
            outputStream.close()
            val base64 = "data:image/png;base64," + outputStream.toByteArray().encodeBase64()
            val markdown = "![$path]($base64)"
            AiTools.ToolData(
                type = AiTools.ToolData.Type.MARKDOWN,
                value = markdown,
            )
        }
    }
}