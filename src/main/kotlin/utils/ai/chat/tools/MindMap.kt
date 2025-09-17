package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import org.intellij.lang.annotations.Language
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object MindMap
{
    private val logger = SubQuizLogger.getLogger<MindMap>()
    @OptIn(ExperimentalUuidApi::class)
    suspend fun makeMindMap(
        @Language("markdown")
        content: String,
    ): String = withContext(Dispatchers.IO)
    {
        val id = Uuid.random()
        val tempFolder = Files.createTempDirectory(id.toHexString())
        try
        {
            val file = tempFolder.resolve("mindmap.md")
            file.toFile().writeText(content)
            val cmd = "markmap --no-open --no-toolbar --offline mindmap.md -o mindmap.html"
            val process = ProcessBuilder("bash", "-c", cmd)
                .directory(tempFolder.toFile())
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0)
            {
                error(String(process.errorStream.readAllBytes()))
            }
            val htmlFile = tempFolder.resolve("mindmap.html")
            if (!htmlFile.exists())
            {
                error("生成思维导图失败，未找到输出文件 mindmap.html")
            }
            val htmlContent = htmlFile.readText()
            return@withContext "<!--show-download-image-->\n$htmlContent"
        }
        finally
        {
            tempFolder.toFile().deleteRecursively()
        }
    }

    @Serializable
    private data class MindMapToolData(
        @JsonSchema.Description("markdown 内容用于生成思维导图")
        val markdown: String,
    )

    init
    {
        val p = ProcessBuilder("bash", "-c", "markmap --version")
        val exitCode = p.start().waitFor()
        if (exitCode != 0) logger.severe("未找到 markmap 命令，请确保已安装 markmap")

        AiTools.registerTool<MindMapToolData>(
            name = "mindmap",
            displayName = "思维导图",
            description = """
                该工具用于从 markdown 内容生成思维导图。
                如用户需要你制作思维导图，或你认为需要以思维导图的形式展示内容时，可以使用该工具。
                该工具会将生成的思维导图直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            val data = parm.markdown
            if (data.isBlank())
            {
                return@registerTool AiToolInfo.ToolResult(
                    Content("error: markdown content must not be empty")
                )
            }

            val bytes = makeMindMap(data).encodeToByteArray()
            val uuid = ChatFiles.addChatFile(chat.id, "mindmap.html", AiTools.ToolData.Type.HTML, bytes)
            return@registerTool AiToolInfo.ToolResult(
                Content("生成思维导图成功，已展示给用户"),
                "uuid:${uuid.toHexString()}",
                AiTools.ToolData.Type.PAGE
            )
        }
    }
}