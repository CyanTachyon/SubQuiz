package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.DocumentConversion
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.resize
import moe.tachyon.quiz.utils.toJpegBytes
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object PPT
{
    private const val PPT_PROMPT = """
以下是你需要遵循的PPT制作规则：

# 格式(**HTML演示文稿（幻灯片）**)
   - 适合具有多个部分的结构化内容
   - 默认尺寸：1280px（宽度）× 720px（高度），横向布局，请仔细计算好每页内容的高度，尽可能保持在720px以内
   - 适合顺序信息显示和演示
   - 你的幻灯片应当是body中的一个直接子元素，类名为"slide"，能被选择器`body > .slide`选中

## 核心原则
- 制作视觉上吸引人的设计
- 强调关键内容：使用关键词而非句子
- 保持清晰的视觉层次
- 通过超大和小元素创建对比
- 保持信息简洁，具有强烈的视觉冲击力

## 演示文稿规划指南
### 整体规划
- 设计一个简短的内容概述，包括核心主题、关键内容、语言风格和内容方法等。
- 当用户上传文档创建幻灯片时，不需要额外信息搜索；将直接基于提供的文档内容进行处理。
- 确定适当的幻灯片数量。
- 如果内容太长，选择主要信息创建幻灯片。
- 根据主题内容和用户要求定义视觉风格，如整体色调、配色/字体方案、视觉元素、排版风格等。在整个设计中使用一致的调色板（最好是Material Design 3，低饱和度）和字体风格。不要从一页到另一页更改主色或字体系列。

### 每页规划
- 页面类型规范（封面页、内容页、图表页等）
- 内容：每页的核心标题和基本信息；避免在一页中放置过多信息。
- 风格：颜色、字体、数据可视化和图表、动画效果（非必须），确保页面之间风格一致，注意封面和结束页的独特布局设计，如标题居中。

# **幻灯片规则**

### 总体规则
1. 使幻灯片具有强烈的视觉吸引力。
2. 当根据材料创建幻灯片时，每页信息应保持简洁，同时注重视觉冲击力。使用关键词而非长句。
3. 保持清晰的层次结构；通过使用更大的字体或数字来强调核心点。大尺寸的视觉元素用于突出关键点，与较小的元素形成对比。但强调文本的大小应小于标题/标题的大小。
- 使用主题的辅助/次要颜色进行强调。将强调限制在每页只有最重要的元素（每页不超过2-3个实例）。
- 不要将关键短语与其周围文本隔离或分开。
3. 处理复杂任务时，首先考虑哪些前端库可以帮助你更高效地工作。
4. 建议使用HTML5、ant-design-vue、Material Design和必要的JavaScript。

### 布局规则
- 避免在一页中添加过多内容，因为它们可能会超过指定的高度，尤其是后面的页面。如果内容过多，考虑将其拆分为多个页面。
- 在适当的地方对齐块以实现视觉一致性，但允许块根据内容缩小或增长，以帮助减少空白空间。
- 为了视觉多样性和避免过度模块化，可以使用超出标准网格的更多样化的布局模式。只要保持整体对齐和视觉层次，鼓励创意安排。
- 页面的主要内容应填满页面的最小高度，避免由于内容高度不足而页脚上移的情况。可以考虑对主容器使用`flex flex-col`，对内容部分使用`flex-grow`来填满所有额外空间。
- 如果有过多的空白空间或视觉空白，可以适当增大字体大小和模块区域以最小化空白间隙。
- 严格限制每页的内容块或详细信息的数量，防止溢出。如果内容超过允许的高度，自动删除或总结优先级最低的项目，但不要省略内容的关键点。
- 可以使用ant-design-vue网格、flexbox、table/table-cell、统一min-height或任何其他合适的CSS技术来实现这一点。
- 在单个幻灯片内，保持主模块/字体/颜色/...风格一致；可以在不同的幻灯片之间使用颜色或图标变化，但在主题配色方案或主风格上保持一致。

### 封面幻灯片规则（第1页）
1. 布局
创建封面幻灯片时，建议尝试以下两种布局：
- 如果将封面标题居中，标题和副标题必须同时实现水平居中和垂直居中。作为最佳实践，在主容器中添加flex justify-center items-center...，并在最外层幻灯片元素或主flex容器上设置height: 100vh以确保真正的垂直居中。
- 如果将封面标题和副标题放在左侧，它们必须实现垂直居中。可以将报告中的几个关键词或数据放在右侧，并应以粗体强调。当有很多关键词时，应遵循Bento Grid的布局设计风格。
- 如果封面包含演讲者和时间等信息，应在中心/左侧均匀对齐。
2. 字体大小：
- 封面标题的大小应为50-70px，根据封面标题的位置和长度进行调整。
- 副标题的大小应为20px。
3. 颜色：
- 调整主色的纯度和亮度，用作标题和副标题文本的颜色。
4. 边距：
- 在封面幻灯片中，左侧内容的最大宽度为70%。
- 左侧内容的padding-left为70px。左侧内容的padding-right为20px。
- 右侧内容的padding-left为20px。右侧内容的padding-right为70px。
5. 幻灯片大小：
- 封面幻灯片的固定宽度应为1280px，高度为720px。
6. 背景图片
- 只有一张图片，带有不透明/半透明蒙版，设置为background-image。

### 内容幻灯片风格规则
- 通常，通过在整个演示过程中使用相同的颜色/字体调色板来保持一致的设计。
1. 颜色
- 建议使用"Material Design 3"调色板，低饱和度。
- 调整主色的纯度和亮度，用作页面的辅助颜色。
- 在整个演示过程中保持一致的设计，使用相同的调色板，一个主色和最多3个辅助色。
2. 图标
- 使用"Material Design Icons"等图标库，通过在头部正确添加带有适当HTML语法的链接。
- 必须通过<link>标签加载Material Icons，如`<link href="https://fonts.googleapis.com/icon?family=Material+Icons" rel="stylesheet">`和`<i class="material-icons">specific_icon_name</i>`
- 禁止使用<script>来显示图标。
- 使用主题颜色作为图标的颜色。不要拉伸图标。
3. 字体
- 不要为了适应更多内容而将字体大小或间距减小到默认设计以下。如果使用多列或模块化布局，确保所有列或块在视觉上对齐并出现相等的高度。
- 根据主题风格和用户要求从Google Fonts库中选择合适且可读的字体。
- 如果没有指定特定风格，严肃场景的建议字体：英文：Source Han Sans SC / Futura / Lenovo-XiaoxinChaokuGB；中文：Douyin Sans / DingTalk JinBuTi / HarmonyOS Sans SC。对于娱乐和有趣的场景，可以使用不同的风格字体。
- 可以对标题和正文使用不同的字体，但在单个PPT中避免使用超过3种字体。
4. 文本可读性：
- 字体大小：页面标题应为40px，正文应为20px。
- 在图像上叠加文本时，添加半透明层以确保可读性。文本和图像需要具有适当的对比度，以确保图像上的文本可以清晰看到。
- 不要对文本应用文本阴影或发光效果。
- 不要将包含大量文本或图表的图像用作文本内容后面的背景图像。
5. 图表：
- 对于大量数值数据，考虑创建视觉图表和图形。这样做时，利用antV 5.0或Chart.js或ECharts进行有效的数据可视化：`<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>`
- 数据可以参考在线图表组件，风格应与主题一致。当有很多数据图表时，遵循Bento Grid的布局设计风格。
6. 图像
- 如果没有要求，每页图像不是必需的。谨慎使用图像。不要使用不相关或纯装饰性的图像。
- 唯一性：整个演示中的每张图像必须是唯一的。不要重复使用已在前面幻灯片中使用过的图像。
- 质量：优先使用清晰、高分辨率、无水印或长文本的图像。
- 大小：避免小于幻灯片区域15%的图像。如果需要徽标/标志，请使用"Your Logo"或相关图标等文本代替。
- 不要编造/制作或修改图像URL。直接并始终使用搜索图像的URL作为文本的示例插图，并注意调整图像大小。
- 如果没有合适的图像可用，干脆不要放置图像。
- 插入图像时，避免不适当的布局，例如：不要将图像直接放在角落；不要将图像放在文本上以遮挡它或与其他模块重叠；不要以杂乱的方式排列多张图像。

### 约束：
1. **尺寸/画布大小**
- 幻灯片CSS应具有1280px的固定宽度和720px的最小高度，以正确处理垂直内容溢出。不要将高度设置为固定值。
- 请尽量将关键点限制在720px高度内。这意味着不应添加过多内容或框。
- 使用图表库时，确保图表或其容器具有高度约束配置。例如，如果在Chart.js中设置了maintainAspectRatio为false，请为其容器添加高度。
2. 不要截断任何模块或块的内容。如果内容超过允许的区域，尽可能在每个块中显示完整内容，并清楚指示内容是否部分显示（例如，使用省略号或"更多"指示器），而不是剪切项目的一部分。
3. 请忽略所有base64格式的图像，以避免使HTML文件过大。
4. 禁止创建图形时间线结构。不要使用任何可能形成时间线的HTML元素（如<div class="timeline">、<div class="connector">、水平线、垂直线等）。
5. 不要使用SVG、连接线或箭头绘制复杂元素或图形代码，如结构图/示意图/流程图，除非用户要求，使用相关的搜索图像（如果可用）。
6. 不要在代码中绘制地图或添加地图注释。

### 交付要求
- 优先遵循用户对风格/颜色/字体/...的特定要求，而不是上述一般指南
    """

    @Serializable
    private data class NewPPTParams(
        @JsonSchema.Description("PPT标题")
        val title: String,
    )

    @Serializable
    private data class NewPPTPage(
        @JsonSchema.Description("PPT的id，从create_new_ppt获得")
        val id: String,
        @JsonSchema.Description("页面的index")
        val index: Int,
        @JsonSchema.Description("页面的内容，使用html")
        val content: String,
    )

    @Serializable
    private data class UploadPPT(
        @JsonSchema.Description("PPT的id，从create_new_ppt获得")
        val id: String,
    )

    fun getPptDir(chatId: ChatId, pptId: String): File =
        File(File(ChatFiles.getChatFilesDir(chatId), "ppt"), pptId)

    private val logger = SubQuizLogger.getLogger<PPT>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler()
    { _, exception ->
        logger.warning("error in generating ppt", exception)
    })

    init
    {
        AiTools.registerTool<NewPPTParams>(
            name = "create_new_ppt",
            displayName = "创建新PPT",
            description = "创建一个新的PPT文件、将获得PPT的id和后续的操作指南",
            display = { (chat, model, parm) ->
                if (parm != null) Content("创建新PPT: ${parm.title}")
                else Content("创建新PPT")
            }
        )
        { (chat, model, parm) ->
            val id = Uuid.random().toString()
            val pptDir = getPptDir(chat.id, id)
            pptDir.mkdirs()
            File(pptDir, "title.txt").writeText(parm.title)
            return@registerTool AiToolInfo.ToolResult(
                content = Content("已创建新PPT: ${parm.title} (id: $id)。\n" + PPT_PROMPT),
            )
        }

        AiTools.registerTool<NewPPTPage>(
            name = "add_ppt_page",
            displayName = "添加PPT页面",
            description = "向指定的PPT文件添加一个新页面，你可以通过添加一个已有的index来覆盖原来的页面，例如用户要求你修改第3页，你可以只重新添加index为3的页面，再直接调用upload_ppt上传即可",
        )
        { (chat, model, parm) ->
            val pptDir = getPptDir(chat.id, parm.id)
            if (!pptDir.exists() || !pptDir.isDirectory)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("error: ppt id ${parm.id} 不存在，请先创建PPT"),
                )
            }
            val img = DocumentConversion.renderHtmlToImage(parm.content, 15, "body > .slide", false)
            if (img.height * 1.0 / img.width > 720.0 / 1280.0 + 0.01)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("error: 页面高度超过限制，请适当调整页面内容，保持在720px以内，而后重新调用 add_ppt_page"),
                )
            }
            val pageFile = File(pptDir, "${parm.index}.html")
            pageFile.writeText(parm.content)
            return@registerTool AiToolInfo.ToolResult(
                content = Content("已向PPT (id: ${parm.id}) 添加页面 ${parm.index}\n\n如需将PPT生成并上传，请调用 upload_ppt 工具"),
            )
        }

        AiTools.registerTool<UploadPPT>(
            name = "upload_ppt",
            displayName = "上传PPT文件",
            description = "将指定的PPT文件打包上传，生成pptx文件并展示给用户",
            display = { (chat, model, parm) ->
                if (parm != null) Content("合成并上传PPT\n该步骤将花费1～10分钟，请耐心等待")
                else Content()
            }
        )
        { (chat, model, parm) ->
            val pptDir = getPptDir(chat.id, parm.id)
            if (!pptDir.exists() || !pptDir.isDirectory)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("error: ppt id ${parm.id} 不存在，请先创建PPT"),
                )
            }
            val titleFile = File(pptDir, "title.txt")
            val title = if (titleFile.exists() && titleFile.isFile) titleFile.readText() else "New PPT"
            val pages = pptDir.listFiles()
                ?.filter { it.isFile && it.extension == "html" }
                ?.mapNotNull {
                    val index = it.name.removeSuffix(".html").toIntOrNull() ?: return@mapNotNull null
                    NewPPTPage(parm.id, index, it.readText())
                }
                ?.sortedBy { it.index }
                ?: emptyList()
            if (pages.isEmpty())
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("error: ppt id ${parm.id} 没有页面，请先添加页面"),
                )
            }
            val images = pages.map()
            {
                coroutineScope.async()
                {
                    DocumentConversion.renderHtmlToImage(
                        it.content,
                        15,
                        "body > .slide"
                    ).resize(2560, 1440)
                }
            }.awaitAll()
            val pptx = DocumentConversion.imagesToPptx(images)
            val pptUuid = ChatFiles.addChatFile(chat.id, "$title.pptx", AiTools.ToolData.Type.FILE, pptx)
            val imageUuids = images.mapIndexed()
            { index, bytes ->
                ChatFiles.addChatFile(chat.id, "$title-${index + 1}.png", AiTools.ToolData.Type.IMAGE, bytes.toJpegBytes())
            }
            return@registerTool AiToolInfo.ToolResult(
                content = Content("已生成PPT: $title，共${pages.size}页"),
                showingContent = listOf(
                    imageUuids.joinToString("\n") { "uuid:${it.toHexString()}" } to AiTools.ToolData.Type.IMAGE,
                    "uuid:${pptUuid.toHexString()}" to AiTools.ToolData.Type.FILE,
                )
            )
        }
    }
}