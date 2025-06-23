package moe.tachyon.quiz.console.command

import moe.tachyon.quiz.dataClass.UserId.Companion.toUserId
import moe.tachyon.quiz.plugin.contentNegotiation.showJson
import org.jline.reader.Candidate
import moe.tachyon.quiz.utils.SSO as SSOUtils

object SSO: TreeCommand(GetUser, GetUserStatus)
{
    override val description: String get() = "SSO commands"
    object GetUser: Command
    {
        override val args: String get() = "<id>"
        override val description: String get() = "Get user information"
        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
        {
            if (args.size != 1)
            {
                return false
            }
            val token = SSOUtils.getAccessToken(args[0].toUserId()) ?: run {
                sender.out("User not found")
                return true
            }
            val user = SSOUtils.getUserFull(token) ?: run {
                sender.out("User not found")
                return true
            }
            sender.out(showJson.encodeToString(user))
            return true
        }

        override suspend fun tabComplete(args: List<String>): List<Candidate>
        {
            if (args.size == 1) return listOf(Candidate ("<id>"))
            return emptyList()
        }
    }

    object GetUserStatus: Command
    {
        override val args: String get() = "<id>"
        override val description: String get() = "Get user status"
        override suspend fun execute(sender: CommandSet.CommandSender, args: List<String>): Boolean
        {
            if (args.size != 1)
            {
                return false
            }
            val token = SSOUtils.getAccessToken(args[0].toUserId()) ?: run {
                sender.out("User not found")
                return true
            }
            val status = SSOUtils.getStatus(token) ?: run {
                sender.out("User not found")
                return true
            }
            sender.out(showJson.encodeToString(status))
            return true
        }

        override suspend fun tabComplete(args: List<String>): List<Candidate>
        {
            if (args.size == 1) return listOf(Candidate ("<id>"))
            return emptyList()
        }
    }
}