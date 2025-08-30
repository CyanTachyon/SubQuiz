package moe.tachyon.quiz.utils.ai.chat.tools

import kotlinx.serialization.Serializable
import moe.tachyon.quiz.database.Users
import moe.tachyon.quiz.utils.ai.Content
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object GlobalMemory: KoinComponent
{
    private val users: Users by inject()
    @Serializable
    private data class StoreMemoryParm(
        val key: String,
        val value: String
    )

    @Serializable
    private data class GetMemoryParm(
        val key: String
    )

    @Serializable
    private data class UpdateMemoryParm(
        val key: String,
        val value: String
    )

    @Serializable
    private data class RemoveMemoryParm(
        val key: String
    )

    init
    {
        // 存储记忆工具
        AiTools.registerTool()
        { chat, _ ->
            AiToolInfo<StoreMemoryParm>(
                name = "store_global_memory",
                displayName = "存储全局记忆",
                description = """
                    存储一条全局记忆信息，用于记住用户的偏好、重要信息等。
                    这些记忆会在后续对话中保持，帮助AI更好地为用户服务。
                    注意：当用户提及其喜好、个人信息等任何个性化信息时，
                    无需用户允许或用户主动说明，你应该主动存储这些记忆，
                    而不是等到用户明确要求你存储记忆时才存储。
                    你**必须**积极地存储这些记忆，以便后续对话中能够更好地为用户服务。
                    例如：
                    - 当用户提及，"我喜欢蓝色"，你应该立刻存储记忆 key="喜欢的颜色", value="蓝色"
                    - 当用户提及，"我叫小明"，你应该立刻存储记忆 key="名字", value="小明"
                    - 当用户提及其选科、兴趣爱好、家庭成员等信息时，你也应该立刻存储这些记忆
                    你应该尽可能多的存储这些记忆，除非用户明确表示不希望你存储某些信息。
                    你不需要事先征得用户同意，直接存储即可。
                    你应该将这些记忆存储为键值对形式，键名应该简洁明了，能够准确描述记忆内容。
                    你应该避免存储冗长或复杂的记忆，保持记忆简洁有助于后续查询和管理。
                    你应该定期回顾和更新这些记忆，确保记忆的准确性和相关性。
                    如果用户提及的信息与你已有的记忆冲突，你应该更新已有记忆以反映最新信息。
                    你应该避免存储敏感信息，如密码、身份证号等，确保用户隐私和数据安全。
                    你应该确保记忆的安全存储，防止未经授权的访问和泄露。
                    你应该尊重用户的隐私和数据保护法规，确保记忆的合法存储和使用。
                    
                    参数：
                    - key: 记忆的键名，用于标识这条记忆
                    - value: 记忆的内容
                    
                    **重要**：当前和你对话的用户已经储存的记忆的key列表：
                    ${users.getGlobalMemory(chat.user).keys.joinToString(", ") { "`$it`" }}
                    具体的key对应的value你可以通过调用`get_global_memory`工具来获取。
                    所有已有的记忆都是你在之前的对话中主动存储的。
                    请注意，在收到新信息后，你应该立即更新记忆，确保记忆的准确性和相关性。
                    在写入新记忆前，你必须先确认上面的列表中是否已经有相关的key，如有，请不要重复写入，
                    你可以考虑获取并update。你不应该盲目写入大量重复的记忆，这会导致记忆混乱，影响后续对话质量。
                    你应该有选择地存储记忆，确保每条记忆都是有意义且有用的，且不和已有记忆重复。
                    
                """.trimIndent(),
            )
            { parm ->
                val success = users.setGlobalMemory(chat.user, parm.key, parm.value)
                val message = if (success) "已成功存储记忆：${parm.key} = ${parm.value}" else "存储记忆失败，请稍后重试"
                AiToolInfo.ToolResult(Content(message))
            }.let(::listOf)
        }

        // 修改记忆工具
        AiTools.registerTool<UpdateMemoryParm>(
            name = "update_global_memory",
            displayName = "更新全局记忆",
            description = """
                修改已存在的全局记忆信息，或者创建新的记忆。
                如果指定的key已存在，会覆盖原有内容；如果不存在，会创建新记忆。
                
                参数：
                - key: 记忆的键名
                - value: 新的记忆内容
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val success = users.setGlobalMemory(chat.user, parm.key, parm.value)
            val message = if (success) "已成功修改记忆：${parm.key} = ${parm.value}" else "修改记忆失败，请稍后重试"
            AiToolInfo.ToolResult(Content(message))
        }

        // 获取记忆工具
        AiTools.registerTool<GetMemoryParm>(
            name = "get_global_memory",
            displayName = "获取全局记忆",
            description = """
                获取用户的全局记忆信息。
                
                参数：
                - key: 指定要获取的记忆键名
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val memory = users.getGlobalMemory(chat.user)
            
            val result = run()
            {
                val value = memory[parm.key]
                if (value != null) "记忆 '${parm.key}': $value"
                else "未找到键名为 '${parm.key}' 的记忆"
            }
            
            AiToolInfo.ToolResult(Content(result))
        }

        // 删除记忆工具
        AiTools.registerTool<RemoveMemoryParm>(
            name = "remove_global_memory",
            displayName = "删除全局记忆",
            description = """
                删除指定的全局记忆信息。
                
                参数：
                - key: 要删除的记忆键名
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val success = users.removeGlobalMemory(chat.user, parm.key)
            val message = if (success) "已成功删除记忆：${parm.key}" else "删除记忆失败，可能该记忆不存在或发生了其他错误"
            AiToolInfo.ToolResult(Content(message))
        }

        AiTools.registerTool<AiTools.EmptyToolParm>(
            name = "clear_global_memory",
            displayName = "清空全局记忆",
            description = """
                清空用户的所有全局记忆信息。
                此操作不可逆，请谨慎使用。
            """.trimIndent()
        )
        { (chat, model, parm) ->
            val success = users.clearGlobalMemory(chat.user)
            val message = if (success) "已成功清空所有全局记忆" else "清空记忆失败，请稍后重试"
            AiToolInfo.ToolResult(Content(message))
        }
    }
}
