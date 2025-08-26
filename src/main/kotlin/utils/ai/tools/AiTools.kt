package moe.tachyon.quiz.utils.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.plugin.contentNegotiation.contentNegotiationJson
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.generateSchema
import kotlin.reflect.KType
import kotlin.reflect.typeOf

object AiTools
{
    @Serializable data object EmptyToolParm
    typealias ToolGetter = suspend (chat: Chat, model: AiConfig.LlmModel?) -> List<AiToolInfo<*>>
    typealias ToolDataGetter = suspend (chat: Chat, path: String) -> ToolData?
    private val toolGetters = mutableListOf<ToolGetter>()
    private val toolDataGetters = mutableMapOf<String, ToolDataGetter>()
    private val logger = SubQuizLogger.getLogger<AiTools>()
    val aiNegotiationJson = Json(contentNegotiationJson)
    {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    init
    {
        listOf(
            AiLibrary,
            WebSearch,
            GetUserInfo,
            MindMap,
            ShowHtml,
            PPT,
            Math,
            Quiz,
            ReadImage,
            ImageGeneration,
            MCP,
            ShowQuestion,
        ).let()
        {
            logger.fine("Loaded AI Tools: ${it.joinToString { tool -> tool::class.simpleName ?: "Unknown" }}")
        }
    }

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
        }
    }

    suspend fun getData(chat: Chat, type: String, path: String) = toolDataGetters[type]?.invoke(chat, path)

    suspend fun getTools(chat: Chat, model: AiConfig.LlmModel?): List<AiToolInfo<*>> =
        toolGetters.flatMap { it(chat, model) }

    data class ToolStatus<T>(val chat: Chat, val model: AiConfig.LlmModel?, val parm: T)

    inline fun <reified T: Any> registerTool(
        name: String,
        displayName: String?,
        description: String,
        noinline block: suspend (info: ToolStatus<T>) -> AiToolInfo.ToolResult
    ) = registerTool()
    { chat, model ->
        val tool = AiToolInfo<T>(name, displayName, description)
        { parm ->
            block(ToolStatus(chat, model, parm))
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
    val displayName: String?,
    val description: String,
    val invoke: suspend (T) -> ToolResult,
    val dataSchema: JsonSchema,
    val type: KType,
)
{
    data class ToolResult(
        val content: Content,
        val showingContent: List<Pair<String, AiTools.ToolData.Type>> = emptyList(),
    )
    {
        constructor(
            content: Content,
            showingContent: String,
            showingType: AiTools.ToolData.Type = AiTools.ToolData.Type.MARKDOWN
        ): this(
            content = content,
            showingContent = listOf(showingContent to showingType)
        )
    }

    operator fun invoke(parm: String)
    {
        invoke(AiTools.aiNegotiationJson.decodeFromString(parm))
    }

    companion object
    {
        inline operator fun <reified T: Any> invoke(
            name: String,
            displayName: String?,
            description: String,
            noinline block: suspend (T) -> ToolResult
        ): AiToolInfo<T>
        {
            val type = typeOf<T>()
            return AiToolInfo(
                name = name,
                displayName = displayName,
                description = description,
                invoke = block,
                dataSchema = JsonSchema.generateSchema<T>(),
                type = type
            )
        }
    }
}