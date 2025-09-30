package moe.tachyon.quiz.utils.ai

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNode
import io.ktor.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.EssayQuestion
import moe.tachyon.quiz.dataClass.FillQuestion
import moe.tachyon.quiz.dataClass.JudgeQuestion
import moe.tachyon.quiz.dataClass.MultipleChoiceQuestion
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.dataClass.SectionId
import moe.tachyon.quiz.dataClass.SingleChoiceQuestion
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.COS
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import moe.tachyon.quiz.utils.ai.internal.llm.utils.ResultType
import moe.tachyon.quiz.utils.ai.internal.llm.utils.RetryType
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetReply
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetResult
import moe.tachyon.quiz.utils.ai.internal.llm.utils.yamlResultType
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

    suspend fun imageToText(imageUrl: String, onMessage: suspend (msg: String) -> Unit = {}) = sendAiRequest(
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

    /**
     * 识别作文内容
     */
    suspend fun recognizeEssay(imageUrl: String): Pair<String, TokenUsage> = sendAiRequestAndGetReply(
        model = aiConfig.imageModel,
        messages = ChatMessages(
            Role.USER, Content(
                ContentNode.image(imageUrl),
                ContentNode.text("""请你识别图中的作文内容，并输出为纯文本格式，不要做任何额外的解释、或其他额外工作，仅输出作文内容，若有批改等请忽略，若有拼写错误请勿纠正，仅保持原样输出。""")
            )
        )
    )

    /**
     * 识别作文题目要求
     */
    suspend fun recognizeEssayRequirement(imageUrl: String): Pair<String, TokenUsage> = sendAiRequestAndGetReply(
        model = aiConfig.imageModel,
        messages = ChatMessages(
            Role.USER, Content(
                ContentNode.image(imageUrl),
                ContentNode.text("""请你识别图中的作文题目要求，并输出为纯文本格式，不要做任何额外的解释、或其他额外工作，仅输出作文题目要求""")
            )
        )
    )

    @Serializable
    private data class RecognizeSectionResult(
        val id: Int,
        val answers: YamlNode,
    )
    /**
     * 识别section内容
     */
    suspend fun <Answer, Analysis: JsonElement?> recognizeSection(
        imageUrl: String,
        section: Section<Answer, Any?, Analysis>
    ): Pair<Section<Answer, Any?, Analysis>, TokenUsage>
    {
        val sb = StringBuilder()
        sb.appendLine("你现在的任务是，将一张图片中的作答信息识别出")
        sb.appendLine("请严格按照以下要求进行识别：")
        sb.appendLine("1. 你需要识别图片中的所有内容，并将其按要求转换为YAML格式输出")
        sb.appendLine("2. 你需要保留图片中的所有格式信息，包括但不限于加粗、斜体、下划线、删除线、分段、列表、表格、图片等")
        sb.appendLine($$"3. 你需要将图片中的数学公式转换为LaTeX格式，并用$符号包裹")
        sb.appendLine("4. 你不需要对图片中的内容进行任何解释或修改，保持原样输出")

        sb.append("""
            
            以下是题目小题情况:
            
        """.trimIndent())
        section.questions.forEachIndexed()
        { index, question ->
            sb.appendLine("### 小题 ${index + 1}")
            sb.appendLine("题目类型: ${question.type}")
            sb.append("对于本题，你应该输出的的格式为:")
            when (question)
            {
                is EssayQuestion<*, *, *>          -> "一个字符串(markdown)，表示简答题的内容"
                is FillQuestion<*, *, *>           -> "一个字符串(markdown)，表示填空题的内容"
                is JudgeQuestion<*, *, *>          -> "一个布尔值，true表示正确，false表示错误"
                is MultipleChoiceQuestion<*, *, *> -> "一个字符串列表，表示多选题的内容，例如[A, C]"
                is SingleChoiceQuestion<*, *, *>   -> "一个字符串，表示单选题的内容,例如A"
            }
            sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("请你严格按照以下YAML格式输出:")
        sb.append("""
            ```yaml
            - id: ${section.id.value} # 小题ID
              answers: 'A' # 或者 ["A", "C"] 或者 true/false 或者 一个字符串表示简答题/填空题内容 如果不存在请用 null 表示
            - id: ...
              answers: ...
            ...
            ```
        """.trimIndent())
        sb.appendLine()
        sb.appendLine("注意:")
        sb.appendLine("- 你需要严格按照上述格式输出，不要有任何多余的内容")
        sb.appendLine("- 你需要确保输出的YAML格式正确，否则将无法解析")
        sb.appendLine("- 你需要确保你输出的列表和上面给出的题目一一对应")
        sb.appendLine("- 你不需要对图片中的内容进行任何解释或修改，保持原样输出")
        sb.appendLine("- 善用yaml的多行字符串，以减少冗余和字符串转义，以避免转义错误")
        sb.appendLine()
        val message = ChatMessage(Role.USER, Content(ContentNode.image(imageUrl), ContentNode.text(sb.toString())))
        val resultType = object: ResultType<Section<Answer, Any?, Analysis>>
        {
            private val impl = yamlResultType<List<RecognizeSectionResult>>()

            fun toOption(s: String): Int
            {
                val str = s.trim().uppercase()
                var res = 0
                for (c in str)
                {
                    if (c !in 'A'..'Z') error("选项格式错误")
                    val v = c - 'A' + 1
                    res = res * 26 + v
                }
                return res - 1
            }

            override fun getValue(str: String): Section<Answer, Any?, Analysis>
            {
                val list = impl.getValue(str)
                if (list.size != section.questions.size) error("识别结果与题目数量不符，题目数量为${section.questions.size}，识别结果数量为${list.size}")
                if (list.map { it.id }.toSet().size != list.size) error("识别结果中存在重复的小题ID")
                if (list.any { it.id !in (1..section.questions.size) }) error("识别结果中存在无效的小题ID")
                val answers = list.sortedBy { it.id }.map { it.answers }
                return section.copy(
                    questions = section.questions.zip(answers).map()
                    { (q, a) ->
                        when (q)
                        {
                            is EssayQuestion<Answer, Any?, Analysis>          -> q.copy(userAnswer = a.contentToString())
                            is FillQuestion<Answer, Any?, Analysis>           -> q.copy(userAnswer = a.contentToString())
                            is JudgeQuestion<Answer, Any?, Analysis>          -> q.copy(userAnswer = a.contentToString() == "true")
                            is MultipleChoiceQuestion<Answer, Any?, Analysis> -> q.copy(userAnswer = (a as? YamlList)?.items?.map { toOption(it.contentToString()) } ?: error("多选题答案格式错误"))
                            is SingleChoiceQuestion<Answer, Any?, Analysis>   -> q.copy(userAnswer = toOption(a.contentToString()))
                        }
                    }
                )
            }
        }
        return sendAiRequestAndGetResult<Section<Answer, Any?, Analysis>>(
            model = aiConfig.imageModel,
            messages = ChatMessages(message),
            resultType = resultType,
            retryType = RetryType.ADD_MESSAGE,
            record = false,
        ).let { it.first.getOrThrow() to it.second }
    }
}