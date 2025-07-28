package moe.tachyon.quiz.utils.ai.ask

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.internal.sendAiStreamRequest
import java.util.*

class QuizAskService private constructor(val model: AiConfig.Model): AskService()
{
    override suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: ChatMessages,
        user: UserId,
        content: String,
        onRecord: suspend (StreamAiResponseSlice)->Unit
    ): Pair<ChatMessages, TokenUsage>
    {
        val prompt = makePrompt(section, !model.imageable, model.toolable)
        val messages = listOf(prompt) + histories + ChatMessage(Role.USER, content)
        return sendAiStreamRequest(
            model = model,
            messages = messages,
            record = false,
            onReceive = onRecord,
            tools = if (model.toolable) getTools(user) else emptyList(),
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