package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.tachyon.quiz.config.AiConfig
import moe.tachyon.quiz.dataClass.Chat
import moe.tachyon.quiz.logger.SubQuizLogger
import moe.tachyon.quiz.utils.JsonSchema
import moe.tachyon.quiz.utils.ai.Content
import moe.tachyon.quiz.utils.ai.aiNegotiationJson
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

    init
    {
        listOf(
            AiLibrary,
            WebSearch,
            GetUserInfo,
            GlobalMemory,
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

    suspend fun getData(chat: Chat, type: String, path: String): ToolData?
    {
        val getter = toolDataGetters[type]
        logger.fine("got data request: type=$type, path=$path, getter=$getter")
        return getter?.invoke(chat, path)
    }

    suspend fun getTools(chat: Chat, model: AiConfig.LlmModel?): List<AiToolInfo<*>> =
        toolGetters.flatMap { it(chat, model) }

    data class ToolStatus<T>(val chat: Chat, val model: AiConfig.LlmModel?, val parm: T)

    inline fun <reified T: Any> registerTool(
        name: String,
        displayName: String?,
        description: String,
        noinline block: suspend (info: ToolStatus<T>) -> AiToolInfo.ToolResult
    ) = registerTool<T>(
        name = name,
        description = description,
        display = if (displayName != null) ({ displayName to Content() }) else ({ null }),
        block = block,
    )

    inline fun <reified T: Any> registerTool(
        name: String,
        displayName: String,
        description: String,
        noinline display: suspend (info: ToolStatus<T?>) -> Content,
        noinline block: suspend (info: ToolStatus<T>) -> AiToolInfo.ToolResult
    ) = registerTool<T>(
        name = name,
        description = description,
        display = { displayName to display(it) },
        block = block,
    )

    inline fun <reified T: Any> registerTool(
        name: String,
        description: String,
        noinline display: suspend (info: ToolStatus<T?>) -> Pair<String, Content>?,
        noinline block: suspend (info: ToolStatus<T>) -> AiToolInfo.ToolResult
    ) = registerTool()
    { chat, model ->
        val tool = AiToolInfo<T>(name, description, { parm ->
            val r = display(ToolStatus(chat, model, parm))
            r?.let { AiToolInfo.DisplayToolInfo(it.first, it.second) }
        })
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
    val description: String,
    val display: suspend (T?) -> DisplayToolInfo?,
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

    @Serializable
    data class DisplayToolInfo(
        val title: String,
        val content: Content
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(parm: String): T =
        aiNegotiationJson.decodeFromString(aiNegotiationJson.serializersModule.serializer(type), parm) as T

    @Suppress("UNCHECKED_CAST")
    suspend operator fun invoke(parm: String) =
        invoke(parse(parm))

    @Suppress("UNCHECKED_CAST")
    suspend fun display(parm: String): DisplayToolInfo? =
        display(runCatching { parse(parm) }.getOrNull())

    companion object
    {
        inline operator fun <reified T: Any> invoke(
            name: String,
            displayName: String?,
            description: String,
            noinline block: suspend (T) -> ToolResult
        ): AiToolInfo<T> = AiToolInfo(
            name = name,
            description = description,
            display = if (displayName != null) ({ DisplayToolInfo(displayName, Content()) }) else ({ null }),
            block = block,
        )

        inline operator fun <reified T: Any> invoke(
            name: String,
            displayName: String,
            description: String,
            noinline display: suspend (T?) -> Content,
            noinline block: suspend (T) -> ToolResult
        ): AiToolInfo<T> = AiToolInfo(
            name = name,
            description = description,
            display = { DisplayToolInfo(displayName, display(it)) },
            block = block,
        )

        inline operator fun <reified T: Any> invoke(
            name: String,
            description: String,
            noinline display: suspend (T?) -> DisplayToolInfo?,
            noinline block: suspend (T) -> ToolResult
        ): AiToolInfo<T> = AiToolInfo(
            name = name,
            description = description,
            display = display,
            invoke = block,
            dataSchema = JsonSchema.generateSchema<T>(),
            type = typeOf<T>()
        )
    }
}