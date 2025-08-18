package moe.tachyon.quiz.utils.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.Loader
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import org.intellij.lang.annotations.Language
import java.io.File
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readBytes
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
        theme: Theme,
    ): Result = withContext(Dispatchers.IO)
    {
        val id = Uuid.random()
        val tempFolder = Files.createTempDirectory(id.toHexString())

        val themeData = Loader.getResource("/ppt/themes/${theme.id}.css")
        val themeArg =
            if (themeData != null)
            {
                val themeFile = tempFolder.resolve("theme.css")
                themeFile.toFile().writeText("""
                    /*
                     * @theme SubQuiz
                     * @auto-scaling true
                     */
                """.trimIndent() + String(themeData.readAllBytes()))
                "--theme theme.css"
            }
            else
            {
                "--theme ${theme.id}"
            }

        val content =
            if (theme.clazz != null)
            {
                """
                <!--
                class: ${theme.clazz}
                -->
                """.trimIndent() + "\n" + content
            }
            else
            {
                content
            }

        val file = tempFolder.resolve("marp.md")
        file.toFile().writeText(content)

        val cmd = "PUPPETEER_TIMEOUT=0 marp --no-stdin marp.md --images png $themeArg --browser-timeout && PUPPETEER_TIMEOUT=0 marp --no-stdin marp.md --pptx --pptx-editable $themeArg --browser-timeout"
        val process = ProcessBuilder("bash", "-c", cmd)
            .directory(tempFolder.toFile())
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0)
        {
            error(String(process.errorStream.readAllBytes()))
        }
        val allImageFile = mutableListOf<File>()
        for (i in 1..999)
        {
            val fileName = "marp.${i.toString().padStart(3, '0')}.png"
            val imageFile = tempFolder.resolve(fileName)
            if (!imageFile.exists()) break
            allImageFile += imageFile.toFile()
        }
        val allImages = allImageFile.map { it.readBytes() }
        val pptxFile = tempFolder.resolve("marp.pptx")
        if (!pptxFile.exists())
        {
            error("生成PPT失败，未找到输出文件 marp.pptx")
        }
        val pptxContent = pptxFile.readBytes()
        return@withContext Result(allImages, pptxContent)
    }

    @Serializable
    private data class ToolParm(
        @JsonSchema.Description("ppt的文件名（不包含后缀）")
        val name: String,
        @JsonSchema.Description("PPT 主题名称，默认为 wave")
        val theme: String = "wave",
        @JsonSchema.Description("markdown 内容用于生成 PPT")
        val markdown: String,
    )

    private data class Theme(
        val id: String,
        val clazz: String? = null,
        val description: String,
        val name: String = id,
    )

    private val themes = listOf(
        Theme(id = "wave", description = "一个蓝色波浪主题，适合用于展示科技、海洋等相关内容的PPT。该主题使用了蓝色波浪作为背景，给人一种清新、现代的感觉。"),

        Theme(id = "default", description = "默认主题"),

        Theme(id = "academic", description = "The academic theme features a clean design with a maroon ribbon on top. Additionally, it leverages the Markdown blockquote syntax (e.g., > text) to enable footnotes. The theme imports Noto Sans with support for Japanese and Source Code Pro."),
        Theme(id = "beam", description = "The beam theme tries to mimic the look of LaTeX’s beamer class. As such, it is intended to be used with the Computer Modern Unicode font family. It also provides support for a title page. To change the default colors from blue to anything else, please, follow the guide in the GitHub repository of the theme."),
        Theme(id = "border", description = "This theme is based on the default Marp theme but contains some notable changes. Each slide has a dark-gray border and also a white-to-gray linear gradient as its background. As the main font, the Inter font family is used."),
        Theme(id = "gaia", description = "Gaia is a built-in theme in Marp featuring a minimalistic color palette of cornsilk (light yellow) and deep space sparkle (greenish grey)."),
        Theme(id = "gaia", clazz = "invert", name = "gaia-invert", description = "与gaia主题相反的配色方案，gaia为浅色背景，gaia-invert为深色背景"),
        Theme(id = "gradient", description = "This theme is based on the default Marp theme. The background on each slide is a colorful diagonal gradient—either shades of blue or pink to light green. As the main font, the Inter font family is used."),
        Theme(id = "gradient", clazz = "blue", name = "gradient-blue", description = "与gradient主题的区别是，gradient为浅粉色渐变，而 gradient-blue为浅蓝色渐变"),
        Theme(id = "graph_paper", description = "The graph_paper theme features a subtle graph paper-like background with dark text mixed with white elements (e.g., code blocks or block quotes). The Work Sans font family is imported and utilized to enhance the visual appeal and readability of the text."),

        Theme(id = "rose-pine", description = "This theme implements the Rosé Pine color palette for Marp. It features soft natural colors with a dark background, offering a soothing aesthetic to your presentation slides."),
        Theme(id = "rose-pine-dawn", description = "This theme implements the Dawn variant of the Rosé Pine color palette for Marp. It features soft natural colors with a light background and illustrates that familiar feeling of a waking up early on a sunny day."),
        Theme(id = "rose-pine-moon", description = "This theme implements the Moon variant of the Rosé Pine color palette for Marp. It features a dark background with soft natural colors illuminating the slide much like a tapestry of neon hues on an empty city street at night."),

        Theme(id = "uncover", description = "Uncover is a built-in theme in Marp featuring a stylish black and white color palette with centered elements."),
        Theme(id = "uncover", clazz = "invert", name = "uncover-invert", description = "与uncover主题相反的配色方案，uncover为白色背景，uncover-invert为黑色背景"),

        Theme(id = "dracula", description = "A Dark theme for Marp. Explore the many features of Marp in style! Daniel Nicolas Gisolfi"),
    )

    init
    {
        var p = ProcessBuilder("bash", "-c", "marp --version")
        var exitCode = p.start().waitFor()
        if (exitCode != 0) logger.severe("未找到 marp 命令，请确保已安装 marp")
        p = ProcessBuilder("bash", "-c", "google-chrome --version")
        exitCode = p.start().waitFor()
        if (exitCode != 0) logger.severe("未找到 google-chrome 命令，请确保已安装 Google Chrome 浏览器")

        AiTools.registerTool<AiTools.EmptyToolParm>(
            name = "list_ppt_themes",
            displayName = "读取PPT主题",
            description = """
                该工具用于列出可用的 PPT 主题。
                你可以使用该工具来查看当前支持的 PPT 主题列表。
                返回的主题列表将包含主题的名称和描述。
            """.trimIndent(),
        )
        {
            val themeList = themes.joinToString("\n") { theme ->
                "- **${theme.name}**: ${theme.description}"
            }
            return@registerTool AiToolInfo.ToolResult(
                Content("可用的 PPT 主题列表：\n$themeList")
            )
        }

        AiTools.registerTool<ToolParm>(
            name = "ppt",
            displayName = "生成PPT",
            description = """
                该工具用于从 markdown 内容生成 PPT。
                如用户需要你制作PPT，可以使用该工具。
                默认的主题是 `wave`，该主题在多数情况下都能正常工作，如没有特别要求，
                建议使用该主题。
                若你希望更换主题，请先使用 `list_ppt_themes` 工具查看可用的主题列表，
                你使用的主题必须是从 `list_ppt_themes` 工具中获取的主题中的一个。
                该工具会将生成的 PPT 直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
                相邻的ppt页面之间需用 `---` 分隔。
                请注意：每个页面内容不得超过5条，否则将无法显示
            """.trimIndent(),
        )
        { (chat, parm) ->
            val data = parm.markdown
            if (data.isBlank())
            {
                return@registerTool AiToolInfo.ToolResult(
                    Content("error: markdown content must not be empty")
                )
            }

            val res = makePPT(data, themes.firstOrNull { it.name == parm.theme } ?: themes.first { t -> parm.name == "wave" })
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