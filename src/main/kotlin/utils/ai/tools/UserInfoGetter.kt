package moe.tachyon.quiz.utils.ai.tools

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import moe.tachyon.quiz.dataClass.UserId
import moe.tachyon.quiz.utils.SSO
import moe.tachyon.quiz.utils.ai.AiToolInfo
import moe.tachyon.quiz.utils.ai.Content

@Serializable
data object UserInfoGetter
{
    private class Getter(val user: UserId)
    {
        suspend fun getInfo(): String
        {
            val token = SSO.getAccessToken(user) ?: return "用户未登录或登录信息已过期"
            val userInfo = SSO.getUserFull(token) ?: return "无法获取用户信息，请检查登录状态或联系管理员"
            return """
                用户ID: ${userInfo.id}
                昵称: ${userInfo.username}
                注册时间: ${Instant.fromEpochMilliseconds(userInfo.registrationTime)}
                邮箱: ${userInfo.email.joinToString(", ")}
                实名信息：${userInfo.seiue.joinToString(", ") { "${it.realName} (${it.studentId})${if (it.archived) " (已归档)" else ""}" }}
                
                ${
                    if (userInfo.seiue.size > 1) 
                        "请注意，用户绑定多个实名信息，学工号和姓名是一一对应的。" +
                        "若出现多个学工号同姓名，可能是因为用户在不同学校或部门有多个身份，" +
                        "若出现多个不同姓名，可能是该账户绑定了多个实名信息。"
                    else ""
                }
            """.trimIndent()
        }
    }

    init
    {
        AiTools.registerTool()
        { user ->
            AiToolInfo<UserInfoGetter>(
                name = "user_info",
                displayName = "获取用户信息",
                description = """
                    获取当前正在和你对话的用户的信息
                    
                    将获得用户的ID、昵称、实名、学号/工号等信息。
                """.trimIndent()
            )
            {
                val getter = Getter(user)
                val info = getter.getInfo()
                AiToolInfo.ToolResult(Content(info))
            }
        }
    }
}