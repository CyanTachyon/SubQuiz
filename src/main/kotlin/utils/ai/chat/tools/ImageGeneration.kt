package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.imageGeneration.sendImageGenerationRequest
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
        @JsonSchema.Description("图片的url，支持使用 `uuid:` 前缀来引用系统中的文件，如果传入将基于该图片进行生成")
        val image: String? = null,
        @JsonSchema.Description("第二张图片的url，支持使用 `uuid:` 前缀来引用系统中的文件，如果传入将基于该图片进行生成")
        val image2: String? = null,
        @JsonSchema.Description("第三张图片的url，支持使用 `uuid:` 前缀来引用系统中的文件，如果传入将基于该图片进行生成")
        val image3: String? = null,
    )

    init
    {
        AiTools.registerTool<Parm>(
            name = "generate_image",
            displayName = "生成图片",
            description = """
                调用图片生成模型，根据提示词生成图片。
                该工具会将生成的图片直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
                你可以传入image来实现图片编辑，否则会进行图片生成。
                你可以传入多个图片，然后提示“让图1中的猫和图2中的狗一起玩耍”、“把图1中的人换成图2中的人”等等。
                你可以传入negative_prompt来让生成的图片避开某些元素。
            """.trimIndent(),
        )
        { (chat, model, parm) ->
            val images = sendImageGenerationRequest(
                model = if (parm.image == null) aiConfig.imageGenerator else aiConfig.imageEditor,
                prompt = parm.prompt,
                negativePrompt = parm.negativePrompt,
                image = parm.image?.let { ChatFiles.parseUrl(chat.id, it) },
                image2 = parm.image2?.let { ChatFiles.parseUrl(chat.id, it) },
                image3 = parm.image3?.let { ChatFiles.parseUrl(chat.id, it) },
            )
            val uuids = images.map()
            {
                ChatFiles.addChatFile(chat.id, "img.jpeg", AiTools.ToolData.Type.IMAGE, it)
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