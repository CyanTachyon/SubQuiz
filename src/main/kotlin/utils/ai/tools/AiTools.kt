package moe.tachyon.quiz.utils.ai.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.utils.ai.AiToolInfo

object AiTools
{
    private val toolGetters = mutableListOf<(user: UserId) -> AiToolInfo<*>>()
    private val toolDataGetters = mutableMapOf<String, (user: UserId, path: String) -> ToolData?>()

    init
    {
        AiLibrary
        WebSearch
        UserInfoGetter
        MindMap
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
        }
    }

    fun getData(user: UserId, type: String, path: String) = toolDataGetters[type]?.invoke(user, path)

    fun getTools(user: UserId): List<AiToolInfo<*>> = toolGetters.map { it(user) }

    fun <T: Any> registerTool(tool: AiToolInfo<T>)
    {
        this.toolGetters += { tool }
    }

    inline fun <reified T: Any> registerTool(
        name: String,
        displayName: String,
        description: String,
        noinline block: suspend (T) ->AiToolInfo.ToolResult
    ) = registerTool(AiToolInfo(name, displayName, description, block))

    fun registerTool(getter: (user: UserId) -> AiToolInfo<*>)
    {
        this.toolGetters += getter
    }

    fun registerToolDataGetter(type: String, getter: (user: UserId, path: String) -> ToolData?)
    {
        this.toolDataGetters[type] = getter
    }
}