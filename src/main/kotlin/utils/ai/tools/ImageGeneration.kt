package moe.tachyon.quiz.utils.ai.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.imageGeneration.sendImageGenerationRequest
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object ImageGeneration
{
    @Serializable
    private data class Parm(
        @JsonSchema.Description("图片提示词，例如：一只可爱的猫")
        val prompt: String,
        @JsonSchema.Description("反向提示词，例如：过曝、模糊、噪点、不清晰等")
        val negativePrompt: String,
    )

    init
    {
        AiTools.registerTool<Parm>(
            name = "generate_image",
            displayName = "生成图片",
            description = """
                调用图片生成模型，根据提示词生成图片。
                该工具会将生成的图片直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            val images = sendImageGenerationRequest(prompt = parm.prompt, negativePrompt = parm.negativePrompt)
            val byteArrays = images.map()
            {
                val output = ByteArrayOutputStream()
                ImageIO.write(it, "png", output)
                output.flush()
                output.close()
                output.toByteArray()
            }
            val uuids = byteArrays.map()
            {
                ChatFiles.addChatFile(chat.id, "img.png", AiTools.ToolData.Type.IMAGE, it)
            }
            val res = uuids.joinToString("\n") { "uuid:" + it.toHexString() }
            AiToolInfo.ToolResult(
                content = Content("成功生成图片，已展示给用户。"),
                showingContent = res,
                showingType = AiTools.ToolData.Type.IMAGE
            )
        }
    }
}