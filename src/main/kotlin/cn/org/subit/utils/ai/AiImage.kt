package cn.org.subit.utils.ai

import cn.org.subit.config.aiConfig

object AiImage
{
    private const val  IMAGE_TO_MARKDOWN_PROMPT = """
        请你复述图中内容，不要做任何额外的解释,仅复述图中内容,无需理会其内容是什么、是否正确,仅复述内容。
        若图中有公式，请用Katex格式复述公式。行内公式用${"$"}符号包裹，行间公式用${"$$"}符号包裹。
    """
    suspend fun imageToMarkdown(imageUrl: String): AiResponse
    {
        val response = sendAiRequest(
            url = aiConfig.image.url,
            key = aiConfig.image.key,
            model = aiConfig.image.model,
            messages = listOf(AiRequest.Message(Role.USER, listOf(AiRequest.Message.Content.image(imageUrl), AiRequest.Message.Content(IMAGE_TO_MARKDOWN_PROMPT)))),
            maxTokens = aiConfig.image.maxTokens,
        )
        return response
    }
}