package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.ChatMessages
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.ContentNode
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.internal.llm.utils.sendAiRequestAndGetReply
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object ReadImage
{
    @Serializable
    private data class Parm(
        @JsonSchema.Description("一个数组，其中的每一项是一个图片URL，特别的，该工具还支持使用 `uuid:` 前缀来引用系统中的文件。")
        val imageUrls: List<String>,
        @JsonSchema.Description("提示词，将直接传递给模型")
        val prompt: String,
    )

    init
    {
        AiTools.registerTool()
        { chat, model ->
            if (model?.imageable == true) return@registerTool emptyList()
            listOf(AiToolInfo<Parm>(
                name = "read_image",
                displayName = "查看图片",
                description = """
                    如果你需要知道一个/一组图片中的内容，但是你无法直接查看图片内容，你可以使用该工具来获取图片中的内容。
                    该工具会将全部图片和你的提示词一起给一个VLM模型，并将模型的输出结果返回给你。
                    你应该只让vlm模型描述图片内容，而不是进行其他操作，而进一步的操作（如解答用户问题）则应由你自己完成。
                    该工具支持4类图片URL：
                    - 直接的图片URL，如 `https://example.com/image.png`，该URL必须是公开可访问的。
                    - data URL，如 `data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...`，该URL必须是合法的data URL。
                    - 在`/input`目录中的文件的uuid，如 `uuid:123e4567-e89b-12d3-a456-426614174000`，该文件必须是图片文件。
                    - 在`/output`目录中的文件，如`output:example.png`，example.png替换为相对/output目录的路径，该文件必须是图片文件。
                """.trimIndent(),
                display = {
                    if (it == null) return@AiToolInfo Content()
                    Content("图片URL：\n" + it.imageUrls.joinToString("\n") { url -> "- $url" })
                }
            )
            { parm ->
                val (imgUrl, prompt) = parm
                val imgContent = imgUrl.map()
                {
                    val url = ChatFiles.parseUrl(chat.id, it) ?: error("Image URL '$it' not found in chat ${chat.id}")
                    if (url.startsWith("data:") && !url.startsWith("data:image/"))
                        return@AiToolInfo AiToolInfo.ToolResult(Content("错误：URL '$it' 不是图片"))
                    url
                }.map(ContentNode::image)
                val sb = StringBuilder()
                sb.append(prompt)
                sb.append("\n\n")
                sb.append(
                    """
                    注意：不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅按要求输出图片内容。
                    不要添加任何额外的解释或信息。
                    保持原图的格式和内容，尽可能详细地描述图片中的所有内容。
                    不要对图片中的内容做额外的解释或分析，仅回答图片中的内容。
                """.trimIndent()
                )
                val content = Content(imgContent + ContentNode.text(sb.toString()))
                val description = sendAiRequestAndGetReply(
                    model = aiConfig.imageModel,
                    messages = ChatMessages(Role.USER to content),
                    temperature = 0.1,
                ).first
                return@AiToolInfo AiToolInfo.ToolResult(Content(description))
            })
        }
    }
}