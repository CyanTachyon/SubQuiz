package moe.tachyon.quiz.utils.ai.tools

import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

    fun parseUrl(chat: ChatId, url: String): String? =
        if (!url.startsWith("uuid:")) url
        else ChatFiles.getChatFile(chat, Uuid.parseHex(url.removePrefix("uuid:")))
            ?.let { f -> "data:${f.first.mimeType};base64,${f.second.encodeBase64()}" }

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
                    examples:
                    {
                        "imageUrls": ["https://example.com/image1.png", "https://example.com/image2.png"],
                        "prompt": "请你详细描述图片中的内容，注意仅描述图片中的内容，不要添加任何解释或额外信息。"
                    }
                    {
                        "imageUrls": ["uuid:123e4567e89b12d3a456426614174000"],
                        "prompt": "Spotting all the text in the image with line-level, and output in JSON format."
                    }
                    {
                        "imageUrls": ["uuid:123e4567e89b12d3a456426614174000", "https://example.com/image2.png"],
                        "prompt": "请你输出图中文字内容，不做任何额外的解释、或其他额外工作、无需理会其内容是什么、是否正确，仅直接输出文字内容。"
                    }
                """.trimIndent()
            )
            { parm ->
                val (imgUrl, prompt) = parm
                val imgContent = imgUrl.map()
                {
                    parseUrl(chat.id, it)
                    ?: throw IllegalArgumentException("Image URL '$it' not found in chat ${chat.id}")
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
                val description = sendAiRequest(
                    model = aiConfig.imageModel,
                    messages = ChatMessages(Role.USER to content),
                    temperature = 0.1,
                ).first.content.toText()
                return@AiToolInfo AiToolInfo.ToolResult(Content(description))
            })
        }
    }
}