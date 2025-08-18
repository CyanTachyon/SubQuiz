package moe.tachyon.quiz.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable

@Serializable
data class McpConfig(
    val mcp: List<McpServerConfig> = emptyList()
)
{
    @Serializable
    enum class Type
    {
        SSE,
        STDIO,
        WEBSOCKET,
    }
    @Serializable
    data class McpServerConfig(
        val type: Type,
        @YamlComment("若为SSE或WEBSOCKET类型，则为连接地址(url)，否则为命令行命令(cmd)")
        val path: String,
        @YamlComment("若为STDIO类型，则为命令行参数(args)，否则无效")
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val enable: Boolean = true,
    )
}

val mcpConfig: McpConfig by config("mcp.yml", McpConfig(
    listOf(
        McpConfig.McpServerConfig(
            type = McpConfig.Type.SSE,
            path = "http://localhost:8080/mcp",
            enable = false
        )
    )
))