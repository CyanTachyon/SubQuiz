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
        val value: String?
    )

    init
    {
        AiTools.registerTool()
        { chat, _ ->
            AiToolInfo<StoreMemoryParm>(
                name = "set_global_memory",
                displayName = "设置全局记忆",
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
                    你记录的主语应当是用户，例如key="喜欢的颜色"表示用户喜欢的颜色。
                    若你要记录他人，例如用户的朋友，请明确标出，如key="朋友小明喜欢的颜色"。
                    
                    参数：
                    - key: 记忆的键名，用于标识这条记忆
                    - value: 记忆的内容，若设置为null或空字符串，则表示删除该记忆。
                    你可以通过设置已有的key来覆盖已有的记忆。
                    
                    所有已有的记忆都是你在之前的对话中主动存储的。
                    请注意，在收到新信息后，你应该立即更新记忆，确保记忆的准确性和相关性。
                    在写入新记忆前，你必须先确认上面的列表中是否已经有相关的key，如有，请不要重复写入，
                    你可以考虑获取并update。你不应该盲目写入大量重复的记忆，这会导致记忆混乱，影响后续对话质量。
                    你应该有选择地存储记忆，确保每条记忆都是有意义且有用的，且不和已有记忆重复。
                    
                """.trimIndent(),
                display = { parm ->
                    if (parm != null && !parm.value.isNullOrBlank())
                        Content("存储条目: `${parm.key}` = `${parm.value}`")
                    else if (parm != null)
                        Content("删除条目: `${parm.key}`")
                    else Content()
                }
            )
            { parm ->
                val success =
                    if (parm.value.isNullOrBlank())
                        users.removeGlobalMemory(chat.user, parm.key)
                    else
                        users.setGlobalMemory(chat.user, parm.key, parm.value)
                val message = if (success) "已成功存储记忆：${parm.key} = ${parm.value}" else "存储记忆失败，请稍后重试"
                AiToolInfo.ToolResult(Content(message))
            }.let(::listOf)
        }
    }
}
