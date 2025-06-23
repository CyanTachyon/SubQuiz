package moe.tachyon.quiz.utils.ai.ask

import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.utils.ai.AiRequest
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.StreamAiResponse
import moe.tachyon.quiz.utils.ai.sendAiStreamRequest

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