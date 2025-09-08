package moe.tachyon.quiz.utils.ai

import io.ktor.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.SectionId
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.COS
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetReply
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object AiImage
{
    private val logger = SubQuizLogger.getLogger<AiImage>()
    private const val IMAGE_TO_MARKDOWN_PROMPT = $$$"""
请你输出图中文字内容，不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅直接输出文字内容。
- 你的输出应当为Markdown格式（但不应使用```包裹，直接输出），如有公式，请用LaTeX格式复述公式。行内公式用$符号包裹，行间公式用$$符号包裹。
    """

    private const val IMAGE_TO_TEXT_PROMPT = """
请你输出图中文字内容，不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅直接输出文字内容。
- 你的输出应当为纯文本格式。
    """

    private const val DESCRIBE_IMAGE_PROMPT = $$$"""
# 角色设定
你是顶尖教育图像分析专家，正在协助AI教师系统完成题目讲解。请严格遵循以下要求描述图片内容：

## 核心指令
**精确识别关键元素**：
请尽可能相信的描述图片中的全部内容，不要遗漏任何细节，尽可能让人阅读你的描述就能完全了解图片。但你不应当对图中内容进行猜测和联想。

## 关键要求
✅ **学术严谨性**：
- 物理量单位必须标注（如 5Ω, 20m/s²）
- 几何关系使用专业术语（如 "△ABC与△DEF相似"）
- 化学方程式用标准格式（如 `2H₂ + O₂ → 2H₂O`）

❌ **禁止行为**：
- 添加解释或解题过程
- 使用模糊描述（如 "几个元件" → 应说 "三个电阻和两个电容"）
- 遗漏数值型数据

## 处理原则
1. 遇到模糊内容时：
   - 可识别部分：描述特征（如 "模糊的希腊字母，疑似θ"）
   - 完全不可读：适当描述标注 "位置[左上角]内容不可识别"
   
2. 多元素关系：
   ```graph
   电压源+ → 电阻R1 → 开关 → 电阻R2 → 电压源-
   ```

3. 特殊题型处理：
   - 函数图像：标注关键点坐标、渐近线、极值点
   - 受力分析：用向量格式描述（如 "F₁=5N@30°"）
    """

    /**
     * 图像转文字
     */
    suspend fun imageToMarkdown(imageUrl: String, onMessage: suspend (msg: String) -> Unit) = sendAiRequest(
        model = aiConfig.imageModel,
        messages = ChatMessages(Role.USER, Content(ContentNode.image(imageUrl), ContentNode(IMAGE_TO_MARKDOWN_PROMPT))),
        temperature = 0.1,
        record = false,
        stream = true,
    )
    {
        it as? StreamAiResponseSlice.Message ?: run()
        {
            logger.severe("Unexpected response slice: $it")
            return@sendAiRequest
        }
        onMessage(it.content)
    }

    suspend fun imageToText(imageUrl: String, onMessage: suspend (msg: String) -> Unit) = sendAiRequest(
        model = aiConfig.imageModel,
        messages = ChatMessages(Role.USER, Content(ContentNode.image(imageUrl), ContentNode(IMAGE_TO_TEXT_PROMPT))),
        temperature = 0.1,
        record = false,
        stream = true,
    )
    {
        it as? StreamAiResponseSlice.Message ?: run()
        {
            logger.severe("Unexpected response slice: $it")
            return@sendAiRequest
        }
        onMessage(it.content)
    }

    /**
     * 描述图像内容
     */
    suspend fun describeImage(imageUrl: String) = sendAiRequestAndGetReply(
        model = aiConfig.imageModel,
        messages = ChatMessages(Role.USER, Content(ContentNode.image(imageUrl), ContentNode(DESCRIBE_IMAGE_PROMPT))),
        temperature = 0.1,
        record = false,
    )

    suspend fun describeImage(sectionId: SectionId, imageHash: String): String
    {
        COS.getImageDescription(sectionId, imageHash)?.let { return it }
        val res = describeImage(COS.getImageUrl(sectionId, imageHash)).first
        COS.putImageDescription(sectionId, imageHash, res)
        return res
    }

    @Serializable
    data class SpottingResult(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val text: String,
        val back: Int,
        val front: Int,
    )

    @Serializable
    private data class SpottingResponse(
        val width: Int,
        val height: Int,
        val results: List<BlockResult>,
    )
    {
        @Serializable
        data class BlockResult(
            @SerialName("bbox_2d")
            val bbox2d: List<Int>,
            val text: String,
            val back: String,
            val front: String,
        )
    }

    suspend fun spottingImage(img: BufferedImage): Pair<List<SpottingResult>, TokenUsage>
    {
        val url = "data:image/jpeg;base64," + ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray().encodeBase64()
        val prompt = """
            Spotting all the text in the image with block-level, and output in JSON format:
            {
                "width": <image width>,
                "height": <image height>,
                "results": [
                    {
                        "bbox_2d": [left, top, right, bottom],
                        "text": "<text in the bounding box>",
                        "back": "<back_ground_color_hex>",
                        "front": "<front_color_hex>"
                    },
                    ...
                ]
            }
        """.trimIndent()
        val r = sendAiRequestAndGetResult<SpottingResponse>(
            model = aiConfig.imageModel,
            messages = ChatMessages(Role.USER, Content(ContentNode.image(url), ContentNode(prompt))),
            record = false,
        ).let { it.first.getOrThrow() to it.second }
        val blocks = r.first.results.map()
        {
            SpottingResult(
                left = it.bbox2d[0],
                top = it.bbox2d[1],
                right = it.bbox2d[2],
                bottom = it.bbox2d[3],
                text = it.text,
                back = it.back.removePrefix("#").toInt(16) and 0xFFFFFF,
                front = it.front.removePrefix("#").toInt(16) and 0xFFFFFF
            )
        }.map()
        {
            it.copy(
                left = (it.left * 1.0 / r.first.width * img.width).roundToInt(),
                right = (it.right * 1.0 / r.first.width * img.width).roundToInt(),
                top = (it.top * 1.0 / r.first.height * img.height).roundToInt(),
                bottom = (it.bottom * 1.0 / r.first.height * img.height).roundToInt(),
            )
        }
        return blocks to r.second
    }
}