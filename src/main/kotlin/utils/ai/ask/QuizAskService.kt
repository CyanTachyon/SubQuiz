package moe.tachyon.quiz.utils.ai.ask

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.utils.ai.AiRequest
import moe.tachyon.quiz.utils.ai.Role
import moe.tachyon.quiz.utils.ai.StreamAiResponse
import moe.tachyon.quiz.utils.ai.sendAiStreamRequest
import java.util.WeakHashMap

class QuizAskService private constructor(val model: AiConfig.Model): AskService()
{
    override suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: List<AiRequest.Message>,
        content: String,
        onRecord: suspend (StreamAiResponse.Choice.Message)->Unit
    )
    {
        val prompt = makePrompt(section, !model.imageable)
        val messages = listOf(prompt) + histories + AiRequest.Message(Role.USER, content)
        sendAiStreamRequest(
            model = model,
            messages = messages,
            record = false,
            onReceive = { it.choices.forEach { c -> onRecord(c.message) } },
        )
    }

    companion object
    {
        private val quizAskServiceMap = WeakHashMap<AiConfig.Model, QuizAskService>()
        fun getService(model: String): QuizAskService?
        {
            if (model !in aiConfig.chats.map { it.model }) return null
            val aiModel = aiConfig.models[model] ?: return null
            return quizAskServiceMap.getOrPut(aiModel) { QuizAskService(aiModel) }
        }
    }
}