package moe.tachyon.quiz.utils.ai.ask

import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.internal.llm.StreamAiResult
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiStreamRequest
import moe.tachyon.quiz.utils.ai.tools.AiTools
import moe.tachyon.quiz.utils.ai.tools.ReadImage
import java.util.*

class QuizAskService private constructor(val model: AiConfig.LlmModel): AskService()
{
    private fun parseImages(chat: ChatId, content: Content, imageable: Boolean): Content
    {
        if (imageable)
        {
            val rContent = content.content.mapNotNull()
            {
                when (it)
                {
                    is ContentNode.Text  -> it
                    is ContentNode.Image ->
                    {
                        ReadImage.parseUrl (chat, it.image.url)?.let { url -> ContentNode.image(url) }
                    }
                }
            }
            return Content(rContent)
        }
        val sb = StringBuilder()
        content.content.forEach()
        {
            when (it)
            {
                is ContentNode.Text -> sb.append(it.text)
                is ContentNode.Image -> sb.append("`image_url='${it.image.url}'`")
            }
        }
        return Content(sb.toString())
    }

    override suspend fun ask(
        chat: Chat,
        content: Content,
        onRecord: suspend (StreamAiResponseSlice)->Unit
    ): StreamAiResult
    {
        val histories = (chat.histories + ChatMessage(Role.USER, content)).map()
        {
            it.copy(content = parseImages(chat.id, it.content, model.imageable))
        }
        val prompt = makePrompt(chat.section, !model.imageable, model.toolable)
        val messages = ChatMessages(prompt) + histories
        val tools =
            if (model.toolable) AiTools.getTools(chat, model)
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