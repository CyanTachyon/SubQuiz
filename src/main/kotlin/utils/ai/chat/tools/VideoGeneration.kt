package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.videoGeneration.getVideoGenerationStatus
import moe.tachyon.quiz.utils.ai.internal.videoGeneration.sendVideoGenerationRequest
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object VideoGeneration: AiToolSet.ToolProvider
{
    override val name: String get() = "生成视频"
    private val logger = SubQuizLogger.getLogger<VideoGeneration>()
    @Serializable
    private data class Parm(
        @JsonSchema.Description("视频提示词，例如：一只可爱的猫")
        val prompt: String,
        @JsonSchema.Description("反向提示词，例如：过曝、模糊、噪点、不清晰等")
        val negativePrompt: String? = null,
        @JsonSchema.Description("图片的url，支持使用 `uuid:` 前缀来引用系统中的文件，如果传入将基于该图片进行生成")
        val image: String? = null,
        @JsonSchema.Description("图片的宽高比，如果传入了图片，则请填写null，否则请在可选选项中选择一个")
        val size: String?,
    )

    override suspend fun AiToolSet.registerTools()
    {
        registerTool<Parm>(
            name = "generate_video",
            displayName = "生成视频",
            description = """
                调用视频生成模型，根据提示词生成视频。
                该工具会将生成的视频直接展示给用户，若成功，会告知你成功，若不成功则告知你错误信息。
                你可以传入image来实现基于图片的生成，否则会进行纯文本生成。
                你可以传入反向提示词negative_prompt来让生成的视频避开某些元素。
                可用的size选项包括：${aiConfig.videoGenerator.sizes}
            """.trimIndent(),
        )
        {
            if (parm.size != null && parm.size !in aiConfig.videoGenerator.sizes)
            {
                return@registerTool AiToolInfo.ToolResult(
                    content = Content("生成视频失败，size参数错误，可用的size选项包括：${aiConfig.videoGenerator.sizes}"),
                )
            }
            val request = sendVideoGenerationRequest(
                prompt = parm.prompt,
                negativePrompt = parm.negativePrompt,
                image = parm.image?.let { ChatFiles.parseUrl(chat.id, it) },
                imageSize = parm.size,
            )
            logger.info("Video generation requested, requestId=${request.first}, token=${request.second}")
            sendMessage("正在生成视频，预计花费5分钟，请稍候...\n\n你可以暂时离开，稍后再回来查看结果。")
            withTimeout(15.minutes)
            {
                while (true)
                {
                    delay(1000)
                    val res = getVideoGenerationStatus(request.first, request.second) ?: continue
                    if (res.isRight) return@withTimeout AiToolInfo.ToolResult(
                        content = Content("生成失败：${res.right}"),
                    )
                    else
                    {
                        val uuid = ChatFiles.addChatFile(chat.id, "video.mp4", AiToolSet.ToolData.Type.VIDEO, res.left)
                        val url = "uuid:" + uuid.toHexString()
                        return@withTimeout AiToolInfo.ToolResult(
                            content = Content("成功生成视频，已展示给用户。"),
                            showingContent = url,
                            showingType = AiToolSet.ToolData.Type.VIDEO
                        )
                    }
                }
                @Suppress("KotlinUnreachableCode")
                error("unreachable")
            }
        }
    }
}