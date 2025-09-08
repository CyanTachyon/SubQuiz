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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object PPT
{
    private val logger = SubQuizLogger.getLogger<PPT>()

    @Suppress("ArrayInDataClass")
    private data class Result(
        val images: List<ByteArray>,
        val pptx: ByteArray
    )

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun makePPT(
        @Language("markdown")
        content: String,
        dark: Boolean,
    ): Result = withContext(Dispatchers.IO)
    {
        val id = Uuid.random()
        val tempFolder = Files.createTempDirectory(id.toHexString())

        val file = tempFolder.resolve("slides.md")
        file.toFile().writeText(content)

        val cmd = "yes | npm i -D playwright-chromium && yes | slidev export --format png --output quiz-images ${if (dark) "--dark" else ""} && yes | slidev export --format pptx --output quiz-pptx"
        val process = ProcessBuilder("bash", "-c", cmd)
            .directory(tempFolder.toFile())
            .start()
        val err= process.errorStream.bufferedReader().readText()
        if (err.isNotBlank()) logger.warning("生成PPT时出现错误：\n$err")
        process.waitFor()
        val allImageFile = tempFolder.resolve("quiz-images").toFile().listFiles()
            ?.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sortedBy { it.nameWithoutExtension.toInt() } ?: emptyList()
        val allImages = allImageFile.map { it.readBytes() }
        val pptxFile = tempFolder.resolve("quiz-pptx.pptx").toFile()
        if (!pptxFile.exists() && err.isNotBlank())
            error(err)
        else if (!pptxFile.exists())
            error("生成PPT失败，未找到输出的pptx")
        val pptxContent = pptxFile.readBytes()
        tempFolder.toFile().deleteRecursively()
        return@withContext Result(allImages, pptxContent)
    }

    @Serializable
    private data class ToolParm(
        @JsonSchema.Description("ppt的文件名（不包含后缀）")
        val name: String,
        @JsonSchema.Description("是否启用黑暗模式")
        val dark: Boolean,
        @JsonSchema.Description("slidev 内容用于生成 PPT，你**必须**先通过搜索等手段学习 slidev 的格式，**不要**使用Seriph或Apple Basic或Default主题")
        val content: String,
    )

    init
    {
        var p = ProcessBuilder("bash", "-c", "slidev --version")
        var exitCode = p.start().waitFor()
        if (exitCode != 0) logger.severe("未找到 slidev 命令，请确保已安装 slidev")
        p = ProcessBuilder("bash", "-c", "google-chrome --version")
        exitCode = p.start().waitFor()
        if (exitCode != 0) logger.severe("未找到 google-chrome 命令，请确保已安装 Google Chrome 浏览器")

        AiTools.registerTool<ToolParm>(
            name = "ppt",
            displayName = "生成PPT",
            description = """
                当你需要生产ppt时，你必须先自行通过搜索等方法学习slidev的格式，之后调用该方法，传递slidev的文本内容，生成ppt。
                ppt将直接呈现给用户。
                - 你必须设置适当的theme、layout等保证ppt美观合适
                - **注意**：**不要**使用Seriph或Apple Basic或Default主题
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            val data = parm.content
            if (data.isBlank())
            {
                return@registerTool AiToolInfo.ToolResult(
                    Content("error: markdown content must not be empty")
                )
            }
            if ("theme" !in data)
            {
                return@registerTool AiToolInfo.ToolResult(
                    Content("error: 主题未设置！请先通过搜索等手段学习slidev的格式，至少设置 theme 以保证ppt美观合适，请重新生成ppt内容后再试！")
                )
            }

            val res = makePPT(data, parm.dark)
            val pptxFile = ChatFiles.addChatFile(chat.id, "${parm.name}.pptx", AiTools.ToolData.Type.FILE, res.pptx)
            val images = res.images.mapIndexed()
            { index, image ->
                ChatFiles.addChatFile(chat.id, "${parm.name}-${index + 1}.png", AiTools.ToolData.Type.IMAGE, image)
            }.joinToString("\n") { "uuid:${it.toHexString()}" }
            return@registerTool AiToolInfo.ToolResult(
                Content("生成 PPT 成功，已展示给用户"),
                listOf(
                    images to AiTools.ToolData.Type.IMAGE,
                    pptxFile.toHexString() to AiTools.ToolData.Type.FILE
                )
            )
        }
    }
}