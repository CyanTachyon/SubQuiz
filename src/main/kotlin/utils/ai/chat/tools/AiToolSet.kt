package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.internal.llm.utils.aiNegotiationJson
import moe.tachyon.quiz.utils.generateSchema
import moe.tachyon.quiz.utils.suspendLazy
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class AiToolSet
{
    @Serializable data object EmptyToolParm
    typealias ToolGetter = suspend (chat: Chat, model: AiConfig.LlmModel?) -> List<AiToolInfo<*>>
    typealias ToolDataGetter = suspend (chat: Chat, path: String) -> ToolData?
    private val toolGetters = mutableListOf<ToolGetter>()
    private val toolDataGetters = mutableMapOf<String, ToolDataGetter>()
    private val logger = SubQuizLogger.getLogger<AiToolSet>()

    interface ToolProvider
    {
        val name: String
        suspend fun AiToolSet.registerTools()
    }

    companion object
    {
        suspend operator fun invoke(vararg toolProvider: ToolProvider) = AiToolSet().apply()
        {
            toolProvider.forEach { addProvider(it) }
        }

        private val defaultToolSet = suspendLazy {
            AiToolSet(
                WebSearch,
                AiLibrary,
                GetUserInfo,
                GlobalMemory,
                ShowQuestion,
                CodeRunner,
                MindMap,
                ShowHtml,
                PPT,
                Math,
                Quiz,
                ImageGeneration,
                VideoGeneration,
                MCP,
                ReadImage
            )
        }
        suspend fun defaultToolSet() = defaultToolSet.invoke()
    }

    suspend fun addProvider(provider: ToolProvider) = provider.run { registerTools() }

    @Serializable
    data class ToolData(
        val type: Type,
        val value: String,
    )
    {
        @Serializable
        enum class Type
        {
            MARKDOWN,
            URL,
            TEXT,
            HTML,
            FILE,
            PAGE,
            IMAGE,
            MATH,
            QUIZ,
            VIDEO
        }
    }

    suspend fun getData(chat: Chat, type: String, path: String): ToolData?
    {
        val getter = toolDataGetters[type]
        logger.fine("got data request: type=$type, path=$path, getter=$getter")
        return getter?.invoke(chat, path)
    }

    suspend fun getTools(chat: Chat, model: AiConfig.LlmModel?): List<AiToolInfo<*>> =
        toolGetters.flatMap { it(chat, model) }

    data class ToolStatus<T>(val chat: Chat, val model: AiConfig.LlmModel?, val parm: T, val sendMessage: suspend (msg: String) -> Unit)

    inline fun <reified T: Any> registerTool(
        name: String,
        displayName: String?,
        description: String,
        noinline block: suspend ToolStatus<T>.() -> AiToolInfo.ToolResult
    ) = registerTool()
    { chat, model ->
        val tool = AiToolInfo<T>(name, description, displayName)
        { parm, sendMessage ->
            block(ToolStatus(chat, model, parm, sendMessage))
        }
        listOf(tool)
    }

    fun registerTool(getter: ToolGetter)
    {
        this.toolGetters += getter
    }

    fun registerToolDataGetter(type: String, getter: ToolDataGetter)
    {
        this.toolDataGetters[type] = getter
    }
}

data class AiToolInfo<T: Any>(
    val name: String,
    val description: String,
    val displayName: String?,
    val invoke: Invoker<T>,
    val dataSchema: JsonSchema,
    val type: KType,
)
{
    typealias Invoker<T> = suspend (parm: T, sendMessage: suspend (msg: String) -> Unit) -> ToolResult
    data class ToolResult(
        val content: Content,
        val showingContent: List<Pair<String, AiToolSet.ToolData.Type>> = emptyList(),
    )
    {
        constructor(
            content: Content,
            showingContent: String,
            showingType: AiToolSet.ToolData.Type = AiToolSet.ToolData.Type.MARKDOWN
        ): this(
            content = content,
            showingContent = listOf(showingContent to showingType)
        )
    }

    @Serializable
    data class DisplayToolInfo(
        val title: String,
        val content: Content
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(parm: String): T =
        aiNegotiationJson.decodeFromString(aiNegotiationJson.serializersModule.serializer(type), parm) as T

    @Suppress("UNCHECKED_CAST")
    suspend operator fun invoke(parm: String, sendMessage: suspend (msg: String) -> Unit) =
        invoke(parse(parm), sendMessage)

    companion object
    {
        inline operator fun <reified T: Any> invoke(
            name: String,
            description: String,
            displayName: String?,
            noinline block: Invoker<T>
        ): AiToolInfo<T> = AiToolInfo(
            name = name,
            description = description,
            displayName = displayName,
            invoke = block,
            dataSchema = JsonSchema.generateSchema<T>(),
            type = typeOf<T>()
        )
    }
}