package moe.tachyon.quiz.utils.ai.ask

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Section
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.internal.llm.StreamAiResult
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiStreamRequest
import moe.tachyon.quiz.utils.ai.tools.AiTools
import java.util.*

class QuizAskService private constructor(val model: AiConfig.LlmModel): AskService()
{
    override suspend fun ask(
        section: Section<Any, Any, String>?,
        histories: ChatMessages,
        user: UserId,
        content: String,
        onRecord: suspend (StreamAiResponseSlice)->Unit
    ): StreamAiResult
    {
        val prompt = makePrompt(section, !model.imageable, model.toolable)
        val messages = listOf(prompt) + histories + ChatMessage(Role.USER, content)
        val tools =
            if (model.toolable) AiTools.getTools(user)
            else emptyList()
        return sendAiStreamRequest(
            model = model,
            messages = messages,
            record = false,
            onReceive = onRecord,
            tools = tools,
        )
    }

    override fun toString(): String = "QuizAskService(model=${model.model})"

    companion object
    {
        private val quizAskServiceMap = WeakHashMap<AiConfig.LlmModel, QuizAskService>()
        fun getService(model: String): QuizAskService?
        {
            if (model !in aiConfig.chats.map { it.model }) return null
            val aiModel = aiConfig.models[model] ?: return null
            return quizAskServiceMap.getOrPut(aiModel) { QuizAskService(aiModel) }
        }
    }
}