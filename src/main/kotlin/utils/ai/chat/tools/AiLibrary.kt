package moe.tachyon.quiz.utils.ai.chat.tools

import io.ktor.util.*
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.rag.Rag
import moe.tachyon.quiz.database.rag.UserRag
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import moe.tachyon.quiz.utils.AiLibraryFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.embedding.sendAiEmbeddingRequest
import moe.tachyon.quiz.utils.ai.internal.rerank.sendRerankRequest
import moe.tachyon.quiz.utils.toJpegBytes
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.max

object AiLibrary: KoinComponent, AiToolSet.ToolProvider
{
    override val name: String get() = "知识库搜索"
    private val logger = SubQuizLogger.getLogger<AiLibrary>()
    private val rag: Rag by inject()
    private val userRag: UserRag by inject()

    suspend fun updateLibrary()
    {
        val indexed = rag.getAllFiles()
        val files = AiLibraryFiles.getAiLibraryFiles(ignoreEmpty = true, fileExtensions = setOf("md"))
        files.forEach()
        { file ->
            updateLibrary(file, "", indexed, null, rag::insert)
        }
    }

    /**
     * 文本类文件后缀名
     */
    val textFileExtensions = setOf("md", "txt", "json", "csv", "xml", "html", "java", "kt", "py", "js", "cpp", "c", "h", "cs", "go", "rs", "swift", "php", "sh", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "sql")
    const val USER_SPLIT_SIZE = 8192
    suspend fun updateUserLibrary(user: UserId)
    {
        val baseDir = AiLibraryFiles.getUserAiLibraryBaseDir(user)
        val indexed = userRag.getAllFiles(user)
        val files = AiLibraryFiles.getAiLibraryFiles(baseDir = baseDir, ignoreEmpty = true, fileExtensions = textFileExtensions)
        files.forEach()
        { file ->
            updateLibrary(file, "", indexed, USER_SPLIT_SIZE)
            { path, vector ->
                userRag.insert(user, path, vector)
            }
        }
    }

    private suspend fun updateLibrary(
        file: AiLibraryFiles.FileInfo,
        prefix: String,
        exclude: Set<String>,
        splitSize: Int? = null,
        insert: suspend (filePath: String, vector: List<Double>)->Unit,
    ): Unit = when (file)
    {
        is AiLibraryFiles.FileInfo.Directory -> file.files.forEach()
        {
            updateLibrary(it, "$prefix/${file.name}", exclude, splitSize, insert)
        }
        is AiLibraryFiles.FileInfo.NormalFile ->
        {
            val filePath = "$prefix/${file.name}"
            if (filePath !in exclude)
            {
                logger.info("Inserting file: $filePath")
                logger.warning("file $filePath insert failed")
                {
                    val content = file.content
                    if (splitSize != null && content.length > splitSize)
                    {
                        content.chunked(splitSize).forEach()
                        { s ->
                            insert(filePath, sendAiEmbeddingRequest(s).first)
                        }
                    }
                    else
                        insert(filePath, sendAiEmbeddingRequest(file.content).first)
                }
            }
            Unit
        }
    }

    suspend fun clear(user: UserId? = null) =
        if (user == null) rag.removeAll()
        else userRag.removeAll(user)

    suspend fun remove(user: UserId? = null, filePath: String, deleteFile: Boolean = false)
    {
        if (user == null)
        {
            rag.remove(filePath.removePrefix("/"))
            rag.remove("/" + filePath.removePrefix("/"))
            if (deleteFile) AiLibraryFiles.removeAiLibraryFile(filePath = filePath)
        }
        else
        {
            userRag.remove(user, filePath.removePrefix("/"))
            userRag.remove(user, "/" + filePath.removePrefix("/"))
            if (deleteFile)
                AiLibraryFiles.removeAiLibraryFile(
                    baseDir = AiLibraryFiles.getUserAiLibraryBaseDir(user),
                    filePath = filePath
                )
        }
    }

    suspend fun getAllFiles(user: UserId? = null): Set<String> =
        if (user == null) rag.getAllFiles()
        else userRag.getAllFiles(user)

    /**
     * 移除所有未被索引的文件
     */
    suspend fun cleanup(user: UserId? = null)
    {
        suspend fun cleanup(
            file: AiLibraryFiles.FileInfo,
            prefix: String,
            include: Set<String>,
            user: UserId?,
        ): Unit = when (file)
        {
            is AiLibraryFiles.FileInfo.Directory  -> file.files.forEach()
            {
                cleanup(it, "$prefix/${file.name}", include, user)
            }

            is AiLibraryFiles.FileInfo.NormalFile ->
            {
                val filePath = "$prefix/${file.name}"
                if (filePath !in include)
                {
                    remove(user, filePath, deleteFile = true)
                }
                Unit
            }
        }

        val indexed = getAllFiles(user)
        val baseDir = if (user == null) AiLibraryFiles.aiLibrary else AiLibraryFiles.getUserAiLibraryBaseDir(user)
        val files = AiLibraryFiles.getAiLibraryFiles(baseDir = baseDir, ignoreEmpty = false)
        files.forEach()
        { file ->
            cleanup(file, "", indexed, user)
        }
    }

    fun insertFile(user: UserId? = null, filePath: String, content: String)
    {
        AiLibraryFiles.saveAiLibraryFile(filePath = filePath, data = content.toByteArray(), baseDir = if (user == null) AiLibraryFiles.aiLibrary else AiLibraryFiles.getUserAiLibraryBaseDir(user))
    }

    fun getFileText(user: UserId?, filePath: String) =
        if (user == null) AiLibraryFiles.getAiLibraryFileText(filePath = filePath)
        else AiLibraryFiles.getAiLibraryFileText(baseDir = AiLibraryFiles.getUserAiLibraryBaseDir(user), filePath = filePath)
    fun getFileBytes(user: UserId?, filePath: String) =
        if (user == null) AiLibraryFiles.getAiLibraryFileBytes(filePath = filePath)
        else AiLibraryFiles.getAiLibraryFileBytes(baseDir = AiLibraryFiles.getUserAiLibraryBaseDir(user), filePath = filePath)

    suspend fun search(user: UserId?, prefix: String, query: String, count: Int): List<Pair<String, Double>>
    {
        val vector = sendAiEmbeddingRequest(query)
        val results =
            if (user == null) rag.query(prefix, vector.first, count)
            else userRag.query(user, prefix, vector.first, count)
        return results
    }

    suspend fun searchInOrder(user: UserId?, prefix: String, query: String, count: Int): List<String>
    {
        val searchCount =
            if (count <= 2) 5
            else if (count <= 5) count * 3
            else if (count <= 10) max(count * 2, 15)
            else max(count + count / 2, 20)

        val results = search(user, prefix, query, searchCount).map()
        { r ->
            AiLibraryFiles.getAiLibraryFileText(
                baseDir = if (user == null) AiLibraryFiles.aiLibrary else AiLibraryFiles.getUserAiLibraryBaseDir(user),
                filePath = r.first
            )?.let { "`path: ${r.first}`\n\n$it" } ?: "${r.first} (文件不存在)"
        }
        if (results.isEmpty()) return listOf("未找到相关文件")
        if (results.size <= count) return results
        return sendRerankRequest(query, results).subList(0, count)
    }

    @Serializable
    private data class LibSearchParm(
        @JsonSchema.Description("搜索内容，注意使用自然语言而非使用关键词，例如： '如何写一篇好的作文' 而非 '作文'")
        val query: String,
        @JsonSchema.Description("返回结果数量, 不填默认为3")
        val count: Int = 3,
    )

    @Serializable
    private data class BookSearchParm(
        @JsonSchema.Description("书籍科目")
        val subject: String = "",
        @JsonSchema.Description("搜索内容，自然语言而非关键词")
        val query: String,
        @JsonSchema.Description("返回结果数量, 不填默认为3")
        val count: Int = 3,
    )

    override suspend fun AiToolSet.registerTools()
    {
        ///// bdfz data /////

        registerTool<LibSearchParm>(
            "bdfz_data_search",
            "搜索北大附中资料",
            """
            在北大附中资料库中搜索相关内容, 将返回相关文档
            搜索的数量由 count 参数决定, 不宜过多，否则获得的文档可能过多，建议2到5条较为合适
            """.trimIndent(),
        )
        {
            sendMessage("搜索北大附中资料: ${parm.query.split(" ").joinToString(" ") { s -> "`$s`" }}")
            val res = searchInOrder(null, "/bdfz/", parm.query, parm.count)
            AiToolInfo.ToolResult(
                Content(
                    showJson.encodeToString(res) +
                    "当你在后面的回答中使用以上信息时，type为 `lib`，path为文档路径，" +
                    "例如:\n<data type=\"lib\" path=\"/path/to/document.md\" />")
            )
        }

        registerToolDataGetter("lib")
        { _, path ->
            val file = getFileText(null, path) ?: return@registerToolDataGetter null
            AiToolSet.ToolData(
                type = if (path.endsWith(".md")) AiToolSet.ToolData.Type.MARKDOWN else AiToolSet.ToolData.Type.TEXT,
                value = file,
            )
        }

        ///// books /////

        registerTool<BookSearchParm>(
            "books_search",
            "搜索教科书",
            """
            在教科书资料库中搜索相关内容, 将返回相关文档.
            subject为科目，该参数可选，若不填则搜索所有科目。建议添加该参数以缩小搜索范围。
            搜索的数量由 count 参数决定, 不宜过多，否则获得的文档可能过多，建议2到5条较为合适
            为保证你的回答的准确性，建议你在需要时调用该工具进行搜索，以确认课本上的表述等以保证回答的准确性
            
            注意：
            - 目前仅支持高中阶段的教科书，若你需要获得非高中阶段的内容，请使用其他工具
            - 当前可用的科目包括: ${AiLibraryFiles.getBookSubjects()}，请确保你的subject参数在这些科目中（若不指定则同时搜索这些科目）
            """.trimIndent(),
        )
        {
            if (parm.subject.isNotBlank())
                sendMessage("查找课本: `${parm.subject}` - `${parm.query}`")
            else
                sendMessage("查找课本: `${parm.query}`")
            val path =
                if (parm.subject.isBlank()) "/books/"
                else "/books/${parm.subject}/"
            val res = searchInOrder(null, path, parm.query, parm.count)
            AiToolInfo.ToolResult(
                Content(
                    showJson.encodeToString(res) +
                    "当你在后面的回答中使用以上信息时，添加信息来源标记，type为 `book`，path为文档路径，" +
                    "例如: <data type=\"book\" path=\"/path/to/book.md\" />"
                )
            )
        }

        registerToolDataGetter("book")
        { _, path ->
            val file = getFileBytes(null, path.removeSuffix(".md") + ".png") ?: return@registerToolDataGetter null
            val base64 = "data:image/jpeg;base64," + file.toJpegBytes().encodeBase64()
            AiToolSet.ToolData(
                type = AiToolSet.ToolData.Type.IMAGE,
                value = base64,
            )
        }

        ///// user library /////

        registerTool<LibSearchParm>(
            "user_library_search",
            "搜索用户提供的资料",
            """
            在用户提供的资料库中搜索，因为用户资料库可能包含任意信息，你应当优先使用user_library_search搜索资料而不是联网搜索或其他信息来源。
            搜索的数量由 count 参数决定, 不宜过多，否则获得的文档可能过多，建议2到5条较为合适
            该工具搜索的是用户自己上传给你的资料，你仅能搜索到当前和你对话的用户上传的资料
            """.trimIndent(),
        )
        {
            sendMessage("搜索资料: ${parm.query.split(" ").joinToString(" ") { s -> "`$s`" }}")
            val res = searchInOrder(chat.user, "", parm.query, parm.count)
            AiToolInfo.ToolResult(
                Content(
                    showJson.encodeToString(res) +
                    "当你在后面的回答中使用以上信息时，type为 `user`，path为文档路径，" +
                    "例如:\n<data type=\"user\" path=\"/path/to/document.md\" />")
            )
        }

        registerToolDataGetter("user")
        { chat, path ->
            val file = getFileText(chat.user, path) ?: return@registerToolDataGetter null
            AiToolSet.ToolData(
                type = AiToolSet.ToolData.Type.MARKDOWN,
                value = if (path.endsWith(".md")) file else "```${path.substringAfterLast(".")}\n$file\n```",
            )
        }
    }
}