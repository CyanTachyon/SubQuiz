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
            model = aiConfig.chat,
            messages = messages,
            record = false,
            onReceive = { it.choices.forEach { c -> onRecord(c.message) } },
        )
    }
}