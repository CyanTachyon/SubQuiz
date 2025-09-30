package moe.tachyon.quiz.utils.ai.chat

import com.charleskorn.kaml.YamlMap
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.config.aiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.utils.Either
import moe.tachyon.quiz.utils.ai.*
import moe.tachyon.quiz.utils.UserConfigKeys.CUSTOM_MODEL_CONFIG_KEY
import moe.tachyon.quiz.utils.UserConfigKeys.FORBID_CHAT_KEY
import moe.tachyon.quiz.utils.UserConfigKeys.FORBID_SYSTEM_MODEL_KEY
import moe.tachyon.quiz.utils.ai.chat.plugins.AiContextCompressor
import moe.tachyon.quiz.utils.ai.chat.plugins.EscapeContentPlugin
import moe.tachyon.quiz.utils.ai.chat.plugins.PromptPlugin
import moe.tachyon.quiz.utils.ai.chat.plugins.toLlmPlugin
import moe.tachyon.quiz.utils.ai.chat.tools.AiTools
import moe.tachyon.quiz.utils.ai.internal.llm.AiResult
import moe.tachyon.quiz.utils.ai.internal.llm.LlmLoopPlugin
import moe.tachyon.quiz.utils.ai.internal.llm.sendAiRequest
import moe.tachyon.quiz.utils.toYamlNode
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.*

class QuizAskService private constructor(val model: AiConfig.LlmModel): AskService()
{
    override suspend fun ask(
        chat: Chat,
        content: Content,
        onRecord: suspend (StreamAiResponseSlice)->Unit
    ): AiResult
    {
        val prompt = makePrompt(chat.section, !model.imageable, model.toolable)
        val messages = ChatMessages(chat.histories + ChatMessage(Role.USER, content))
        val tools =
            if (model.toolable) AiTools.getTools(chat, model)
            else emptyList()
        val plugins = listOf<LlmLoopPlugin>(
            AiContextCompressor(aiConfig.contextCompressorModel, 48 * 1024, 5).toLlmPlugin(),
            EscapeContentPlugin(chat.id),
            PromptPlugin(
                ChatMessage(Role.SYSTEM, prompt),
                ChatMessage(Role.SYSTEM, "*当前时间为${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())}")
            ),
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
        val imageable: Boolean = false,
        val toolable: Boolean = false,
        val key: String,
        val customRequestParms: JsonObject = JsonObject(emptyMap())
    )
    {
        fun toLlmModel(): AiConfig.LlmModel = AiConfig.LlmModel(
            model = model,
            url = url,
            imageable = imageable,
            toolable = toolable,
            key = listOf(key),
            customRequestParms = customRequestParms.toYamlNode() as YamlMap,
        )
    }

    companion object: KoinComponent
    {
        const val CUSTOM_MODEL_NAME = "__custom__"
        private val quizAskServiceMap = WeakHashMap<AiConfig.LlmModel, QuizAskService>()
        private val users: Users by inject()
        suspend fun getService(user: UserId, model: String): Either<AskService, String>
        {
            if (users.getCustomSetting<Boolean>(user, FORBID_CHAT_KEY) == true)
                return Either.Right("你已被禁止使用AI问答功能，如有疑问请联系管理员。")
            if (model == CUSTOM_MODEL_NAME)
            {
                val customModel = get<Users>().getCustomSetting<CustomModelSetting>(user, CUSTOM_MODEL_CONFIG_KEY)?.toLlmModel() ?: return Either.Right("你还没有配置自定义模型，请先在用户设置中配置。")
                return Either.Left(quizAskServiceMap.getOrPut(customModel) { QuizAskService(customModel) })
            }
            if (users.getCustomSetting<Boolean>(user, FORBID_SYSTEM_MODEL_KEY) == true)
                return Either.Right("请设置并使用自定义模型，系统模型已被禁用。")
            if (model !in aiConfig.chats.map { it.model }) return Either.Right("模型'$model'不存在，请检查后重试。")
            val aiModel = aiConfig.models[model] ?: return Either.Right("模型'$model'不存在，请检查后重试。")
            return Either.Left(quizAskServiceMap.getOrPut(aiModel) { QuizAskService(aiModel) })
        }
    }
}