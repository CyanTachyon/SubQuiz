package moe.tachyon.quiz.utils.ai.chat

import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.ChatId
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.utils.ChatFiles
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.ai.ChatMessages.Companion.toChatMessages
import moe.tachyon.quiz.utils.ai.internal.llm.AiResult
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import moe.tachyon.quiz.utils.ai.chat.tools.AiTools
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest
import moe.tachyon.quiz.utils.ai.internal.llm.BeforeLlmRequest.BeforeRequestContext
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin.Context
import moe.tachyon.quiz.utils.ai.internal.llm.model
import moe.tachyon.quiz.utils.ai.internal.llm.requestMessage
import moe.tachyon.quiz.utils.toYamlNode
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.*

class QuizAskService private constructor(val model: AiConfig.LlmModel): AskService()
{
    private class EscapeContentPlugin(private val chat: ChatId): BeforeLlmRequest
    {
        context(_: Context, _: BeforeRequestContext)
        override suspend fun beforeRequest()
        {
            requestMessage = requestMessage.map()
            {
                it.copy(content = parseContent(chat, it.content, model.imageable))
            }.toChatMessages()
        }
    }

    override suspend fun ask(
        chat: Chat,
        content: Content,
        onRecord: suspend (StreamAiResponseSlice)->Unit
    ): AiResult
    {
        val prompt = makePrompt(chat.section, !model.imageable, model.toolable)
        val messages = ChatMessages(prompt) + chat.histories + ChatMessage(Role.USER, content)
        val tools =
            if (model.toolable) AiTools.getTools(chat, model)
            else emptyList()
        val plugins = listOf<LlmLoopPlugin>(
            AiContextCompressor(aiConfig.contextCompressorModel, 10240, 5).toLlmPlugin(),
            EscapeContentPlugin(chat.id),
        )
        return sendAiRequest(
            model = model,
            messages = messages,
            record = false,
            onReceive = onRecord,
            tools = tools,
            plugins = plugins,
            stream = true,
        )
    }

    override fun toString(): String = "QuizAskService(model=${model.model})"

    @Serializable
    data class CustomModelSetting(
        val model: String,
        val url: String,
        val maxToken: Int,
        val imageable: Boolean = false,
        val toolable: Boolean = false,
        val key: String,
        val customRequestParms: JsonObject = JsonObject(emptyMap())
    )
    {
        fun toLlmModel(): AiConfig.LlmModel = AiConfig.LlmModel(
            model = model,
            url = url,
            maxTokens = maxToken,
            imageable = imageable,
            toolable = toolable,
            key = listOf(key),
            customRequestParms = customRequestParms.toYamlNode() as YamlMap,
        )
    }

    companion object: KoinComponent
    {
        const val CUSTOM_MODEL_CONFIG_KEY = "ai.chat.custom_model"
        const val CUSTOM_MODEL_NAME = "__custom__"
        private val quizAskServiceMap = WeakHashMap<AiConfig.LlmModel, QuizAskService>()
        suspend fun getService(user: UserId, model: String): QuizAskService?
        {
            if (model == CUSTOM_MODEL_NAME)
            {
                val customModel = get<Users>().getCustomSetting<CustomModelSetting>(user, CUSTOM_MODEL_CONFIG_KEY)?.toLlmModel() ?: return null
                return quizAskServiceMap.getOrPut(customModel) { QuizAskService(customModel) }
            }
            if (model !in aiConfig.chats.map { it.model }) return null
            val aiModel = aiConfig.models[model] ?: return null
            return quizAskServiceMap.getOrPut(aiModel) { QuizAskService(aiModel) }
        }

        private fun parseContent(chat: ChatId, content: Content, imageable: Boolean): Content
        {
            val res = mutableListOf<ContentNode>()
            val cur = StringBuilder()
            content.content.forEach()
            {
                when (it)
                {
                    is ContentNode.Text  -> cur.append(it.text)
                    is ContentNode.Image ->
                    {
                        if (imageable)
                        {
                            if (cur.isNotEmpty())
                            {
                                res.add(ContentNode(cur.toString()))
                                cur.clear()
                            }
                            ChatFiles.parseUrl(chat, it.image.url)?.let { url -> ContentNode.image(url) }?.let(res::add)
                        }
                        else cur.append("`image={url='${it.image.url}'}`")
                    }

                    is ContentNode.File  -> cur.append("`file={name='${it.file.filename}', url='${it.file.url}'}`")
                }
            }
            if (cur.isNotEmpty()) res.add(ContentNode(cur.toString()))
            return Content(res)
        }
    }
}