package moe.tachyon.quiz.utils.ai.chat.tools

import io.ktor.util.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.utils.*
import moe.tachyon.quiz.utils.ai.ChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.StreamAiResponseSlice
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetReply
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URL
import kotlin.math.sqrt
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object ReadImage: AiToolSet.ToolProvider, KoinComponent
{
    override val name: String get() = "查看图片"
    private val users by inject<Users>()

    @Serializable
    private data class Parm(
        @JsonSchema.Description("一个数组，其中的每一项是一个图片URL，特别的，该工具还支持使用 `uuid:` 前缀来引用系统中的文件。")
        val imageUrls: List<String>,
        @JsonSchema.Description("提示词，将直接传递给模型")
        val prompt: String,
    )

    fun imgs(pdf: ByteArray) = DocumentConversion.documentToImages(pdf)?.let()
    { images ->
        // 总像素数
        val totalPixels = images.sumOf { img -> img.width.toLong() * img.height.toLong() }
        // 总像素数量超过5000万，则进行压缩
        if (totalPixels > 50_000_000)
        {
            val scale = sqrt(50_000_000.0 / totalPixels.toDouble())
            images.map()
            {
                val newWidth = (it.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (it.height * scale).toInt().coerceAtLeast(1)
                it.resize(newWidth, newHeight)
            }
        }
        else images
    }?.map()
    {
        "data:image/jpeg;base64," + it.toJpegBytes().encodeBase64()
    }

    override suspend fun AiToolSet.registerTools()
    {
        registerTool()
        { chat, model ->
            if (model?.imageable == true) return@registerTool emptyList()
            val tool = AiToolInfo<Parm>(
                name = "read_image",
                displayName = "查看图片",
                description = """
                    **如果**你需要知道一个/一组图片/PDF中的内容，但是你无法直接查看图片/PDF内容，你可以使用该工具来获取图片中的内容。
                    注意！，如果你已经能得知图片内容，则不应使用该工具。
                    该工具会将全部图片（PDF则转为图片）和你的提示词一起给一个VLM模型，并将模型的输出结果返回给你。
                    你应该只让vlm模型描述内容，而不是进行其他操作，而进一步的操作（如解答用户问题）则应由你自己完成。
                    该工具支持4类URL：
                    - 直接的图片/PDF URL，如 `https://example.com/image.png`，该URL必须是公开可访问的。
                    - data URL，如 `data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...`，该URL必须是合法的data URL。
                    - 在`/input`目录中的文件的uuid，如 `uuid:123e4567-e89b-12d3-a456-426614174000`，该文件必须是图片或PDF文件。
                    - 在`/output`目录中的文件，如`output:example.png`，example.png替换为相对/output目录的路径，该文件必须是图片或PDF文件。
                """.trimIndent(),
            )
            { parm, sendMessage ->
                val (imgUrl, prompt) = parm

                val sb = StringBuilder()
                sb.append(prompt)
                sb.append("\n\n")
                sb.append("""
                    注意：不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅按要求输出图片内容。
                    不要添加任何额外的解释或信息。
                    保持原图的格式和内容，尽可能详细地描述图片中的所有内容。
                    不要对图片中的内容做额外的解释或分析，仅回答图片中的内容。
                """.trimIndent())
                val imgContent = imgUrl.flatMap()
                {
                    val url = ChatFiles.parseUrl(chat.id, it) ?: error("Image URL '$it' not found in chat ${chat.id}")
                    val bytes =
                        if (url.startsWith("data:"))
                            url.substringAfter(",").decodeBase64Bytes()
                        else
                            URL(url).readBytes()
                    DocumentConversion.documentToImages(bytes) ?: return@AiToolInfo AiToolInfo.ToolResult(Content("不支持的图片/PDF格式，URL：$it"))
                }.let()
                { images ->
                    // 总像素数
                    val totalPixels = images.sumOf { img -> img.width.toLong() * img.height.toLong() }
                    // 总像素数量超过5000万，则进行压缩
                    if (totalPixels > 50_000_000)
                    {
                        val scale = sqrt(50_000_000.0 / totalPixels.toDouble())
                        images.map()
                        {
                            val newWidth = (it.width * scale).toInt().coerceAtLeast(1)
                            val newHeight = (it.height * scale).toInt().coerceAtLeast(1)
                            it.resize(newWidth, newHeight)
                        }
                    }
                    else images
                }.map()
                {
                    "data:image/jpeg;base64," + it.toJpegBytes().encodeBase64()
                }.map(ContentNode::image)
                val content = Content(imgContent + ContentNode.text(sb.toString()))
                val res = StringBuilder()
                val usage = sendAiRequest(
                    model = aiConfig.imageModel,
                    messages = ChatMessages(Role.USER to content),
                    temperature = 0.1,
                    stream = true,
                    onReceive = {
                        if (it is StreamAiResponseSlice.Message)
                        {
                            sendMessage(it.content)
                            res.append(it.content)
                        }
                    }
                ).usage
                runCatching()
                {
                    withContext(NonCancellable)
                    {
                        users.addTokenUsage(chat.user, usage)
                    }
                }
                return@AiToolInfo AiToolInfo.ToolResult(Content(res.toString()))
            }
            listOf(tool)
        }
    }
}