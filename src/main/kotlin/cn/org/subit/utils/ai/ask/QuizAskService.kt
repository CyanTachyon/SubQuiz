package cn.org.subit.utils.ai.ask

import cn.org.subit.config.aiConfig
import cn.org.subit.dataClass.Section
import cn.org.subit.utils.ai.AiRequest
import cn.org.subit.utils.ai.Role
import cn.org.subit.utils.ai.StreamAiResponse
import cn.org.subit.utils.ai.sendAiStreamRequest

object QuizAskService: AskService()
{
    override suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: List<AiRequest.Message>,
        content: String,
        onRecord: suspend (StreamAiResponse.Choice.Message)->Unit
    )
    {
        val prompt = makePrompt(section, true)
        val messages = listOf(prompt) + histories + AiRequest.Message(Role.USER, content)
        sendAiStreamRequest(
            url = aiConfig.chat.url,
            key = aiConfig.chat.key.random(),
            model = aiConfig.chat.model,
            messages = messages,
            maxTokens = aiConfig.chat.maxTokens,
            responseFormat = if (aiConfig.chat.useJsonOutput) AiRequest.ResponseFormat(AiRequest.ResponseFormat.Type.JSON) else null,
            onRecord = { it.choices.forEach { c -> onRecord(c.message) } },
        )
    }
}